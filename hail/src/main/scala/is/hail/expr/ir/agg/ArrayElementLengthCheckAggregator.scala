package is.hail.expr.ir.agg

import is.hail.annotations.{Region, RegionUtils, StagedRegionValueBuilder}
import is.hail.asm4s._
import is.hail.expr.ir._
import is.hail.expr.types.physical._
import is.hail.io.{CodecSpec, InputBuffer, OutputBuffer}
import is.hail.utils._
import is.hail.asm4s.coerce

// initOp args: initOps for nestedAgg, length if knownLength = true
// seqOp args: array, other non-elt args for nestedAgg

case class ArrayElementState(mb: EmitMethodBuilder, nested: Array[AggregatorState], knownLength: Boolean) extends PointerBasedRVAState {
  val container: StateContainer = StateContainer(nested, region)
  val arrayType: PArray = PArray(container.typ)
  private val nStates: Int = nested.length
  override val regionSize: Int = Region.SMALL

  val typ: PTuple = PTuple(FastIndexedSeq(container.typ, arrayType))

  val lenRef: ClassFieldRef[Int] = mb.newField[Int]("arrayrva_lenref")
  val idx: ClassFieldRef[Int] = mb.newField[Int]("arrayrva_idx")
  private val aoff: ClassFieldRef[Long] = mb.newField[Long]("arrayrva_aoff")

  private def regionOffset(eltIdx: Code[Int]): Code[Int] = (eltIdx + 1) * nStates

  private val initStatesOffset = typ.loadField(region, off, 0)
  private def initStateOffset(idx: Int): Code[Long] = container.getStateOffset(initStatesOffset, idx)

  private def statesOffset(eltIdx: Code[Int]): Code[Long] = arrayType.loadElement(region, typ.loadField(region, off, 1), eltIdx)

  override def createState: Code[Unit] = Code(
    super.createState,
    container.toCode((_, s) => s.createState))

  override def load(regionLoader: Code[Region] => Code[Unit], src: Code[Long]): Code[Unit] = {
    Code(super.load(regionLoader, src),
      off.ceq(0L).mux(Code._empty,
        lenRef := typ.isFieldMissing(off, 1).mux(-1,
          arrayType.loadLength(region, typ.loadField(region, off, 1)))))
  }

  private val initArray: Code[Unit] =
    Code(
      region.setNumParents((lenRef + 1) * nStates),
      aoff := region.allocate(arrayType.contentsAlignment, arrayType.contentsByteSize(lenRef)),
      region.storeAddress(typ.fieldOffset(off, 1), aoff),
      arrayType.initialize(aoff, lenRef, idx),
      typ.setFieldPresent(region, off, 1))

  def seq(init: Code[Unit], initPerElt: Code[Unit], seqOp: (Int, AggregatorState) => Code[Unit]): Code[Unit] =
    Code(
      init,
      idx := 0,
      Code.whileLoop(idx < lenRef,
        initPerElt,
        container.toCode(seqOp),
        store(idx),
        idx := idx + 1))

  def seq(seqOp: (Int, AggregatorState) => Code[Unit]): Code[Unit] =
    seq(initArray, container.newStates, seqOp)

  def initLength(len: Code[Int]): Code[Unit] = {
    Code(lenRef := len, seq((i, s) => s.copyFrom(initStateOffset(i))))
  }

  def checkLength(len: Code[Int]): Code[Unit] = {
    val check =
      lenRef.ceq(len).mux(Code._empty,
        Code._fatal("mismatched lengths in ArrayElementsAggregator"))

    if (knownLength) check else (lenRef < 0).mux(initLength(len), check)
  }

  def init(initOp: Array[Code[Unit]], initLen: Boolean = !knownLength): Code[Unit] = {
      Code(
        region.setNumParents(nStates),
        off := region.allocate(typ.alignment, typ.byteSize),
        container.newStates,
        container.toCode((i, _) => initOp(i)),
        container.store(0, initStatesOffset),
        if (initLen) typ.setFieldMissing(off, 1) else Code._empty)
  }

  def loadInit: Code[Unit] =
    container.load(0, initStatesOffset)

  def load(eltIdx: Code[Int]): Code[Unit] =
    container.load(regionOffset(eltIdx), statesOffset(eltIdx))

  def store(eltIdx: Code[Int]): Code[Unit] =
    container.store(regionOffset(eltIdx), statesOffset(eltIdx))

  def serialize(codec: CodecSpec): Code[OutputBuffer] => Code[Unit] = {
    val serializers = nested.map(_.serialize(codec));
    { ob: Code[OutputBuffer] =>
      Code(
        loadInit,
        container.toCode((i, _) => serializers(i)(ob)),
        ob.writeInt(lenRef),
        idx := 0,
        Code.whileLoop(idx < lenRef,
          load(idx),
          container.toCode((i, _) => serializers(i)(ob)),
          idx := idx + 1))
    }
  }

  def deserialize(codec: CodecSpec): Code[InputBuffer] => Code[Unit] = {
    val deserializers = nested.map(_.deserialize(codec));
    { ib: Code[InputBuffer] =>
        Code(
          init(deserializers.map(_(ib)), initLen = false),
          lenRef := ib.readInt(),
          (lenRef < 0).mux(
            typ.setFieldMissing(off, 1),
            seq((i, _) => deserializers(i)(ib))))
    }
  }

  def copyFromAddress(src: Code[Long]): Code[Unit] = {
    val srcOff = mb.newField[Long]
    val initOffset = typ.loadField(srcOff, 0)
    val eltOffset = arrayType.loadElement(typ.loadField(srcOff, 1), idx)

    Code(
      srcOff := src,
      init(Array.tabulate(nStates)(i =>
        nested(i).copyFrom(container.getStateOffset(initOffset, i)))),
        lenRef := arrayType.loadLength(typ.loadField(srcOff, 1)),
        (lenRef < 0).mux(
          typ.setFieldMissing(off, 1),
          seq((i, s) => s.copyFrom(container.getStateOffset(eltOffset, i)))))
  }
}

class ArrayElementLengthCheckAggregator(nestedAggs: Array[StagedRegionValueAggregator], knownLength: Boolean) extends StagedRegionValueAggregator {
  type State = ArrayElementState
  private val nStates: Int = nestedAggs.length

  var initOpTypes: Array[PType] = nestedAggs.flatMap(_.initOpTypes)
  if (knownLength)
    initOpTypes = PInt32() +: initOpTypes
  val seqOpTypes: Array[PType] = Array(PInt32())

  val resultEltType: PTuple = PTuple(nestedAggs.map(_.resultType))
  val resultType: PArray = PArray(resultEltType)

  def createState(mb: EmitMethodBuilder): State = ArrayElementState(mb, nestedAggs.map(_.createState(mb)), knownLength)

  // inits all things
  def initOp(state: State, init: Array[RVAVariable], dummy: Boolean): Code[Unit] = {
    var i = if (knownLength) 1 else 0
    val initOps = Array.tabulate(nStates) { sIdx =>
      val agg = nestedAggs(sIdx)
      val vars = init.slice(i, i + agg.initOpTypes.length)
      i += agg.initOpTypes.length
      agg.initOp(state.nested(sIdx), vars)
    }

    if (knownLength) {
      val len = init.head
      assert(len.t isOfType PInt32())
      Code(state.init(initOps), len.setup,
        state.initLength(len.m.mux(Code._fatal("Array length can't be missing"), len.v[Int])))
    } else {
      Code(state.init(initOps), state.lenRef := -1)
    }
  }

  //does a length check on arrays
  def seqOp(state: State, seq: Array[RVAVariable], dummy: Boolean): Code[Unit] = {
    val Array(len) = seq
    assert(len.t isOfType PInt32())

    Code(len.setup, len.m.mux(Code._empty, state.checkLength(len.v[Int])))
  }

  def combOp(state: State, other: State, dummy: Boolean): Code[Unit] = {
    state.seq((other.lenRef < 0).mux(
      (state.lenRef < 0).mux(
        Code._empty,
        other.initLength(state.lenRef)),
      state.checkLength(other.lenRef)),
      Code(other.load(state.idx), state.load(state.idx)),
      (i, s) => nestedAggs(i).combOp(s, other.nested(i)))
  }

  def result(state: State, srvb: StagedRegionValueBuilder, dummy: Boolean): Code[Unit] =
    (state.lenRef < 0).mux(
      srvb.setMissing(),
      srvb.addArray(resultType, { sab =>
        Code(
          sab.start(state.lenRef),
          Code.whileLoop(sab.arrayIdx < state.lenRef,
            sab.addBaseStruct(resultEltType, { ssb =>
              Code(
                ssb.start(),
                state.load(sab.arrayIdx),
                state.container.toCode { (i, s) =>
                  Code(nestedAggs(i).result(s, ssb), ssb.advance())
                })
            }),
            sab.advance()))
      })
    )
}

class ArrayElementwiseOpAggregator(nestedAggs: Array[StagedRegionValueAggregator]) extends StagedRegionValueAggregator {
  type State = ArrayElementState

  def initOpTypes: Array[PType] = Array()
  def seqOpTypes: Array[PType] = Array(PInt32(), PVoid)

  def resultType: PType = PArray(PTuple(nestedAggs.map(_.resultType)))

  def createState(mb: EmitMethodBuilder): State =
    throw new UnsupportedOperationException(s"State must be created by ArrayElementLengthCheckAggregator")

  def initOp(state: State, init: Array[RVAVariable], dummy: Boolean): Code[Unit] =
    throw new UnsupportedOperationException("State must be initialized by ArrayElementLengthCheckAggregator.")

  def seqOp(state: State, seq: Array[RVAVariable], dummy: Boolean): Code[Unit] = {
    val Array(eltIdx, seqOps) = seq
    assert((eltIdx.t isOfType PInt32()) && (seqOps.t == PVoid))
    val eltIdxV = state.mb.newField[Int]
    Code(
      eltIdx.setup,
      eltIdx.m.mux(
        Code._empty,
        Code(
          eltIdxV := eltIdx.v[Int],
          (eltIdxV > state.lenRef || eltIdxV < 0).mux(
            Code._fatal("element idx out of bounds"),
            Code(
              state.load(eltIdxV),
              seqOps.setup,
              state.store(eltIdxV))))))
  }

  def combOp(state: State, other: State, dummy: Boolean): Code[Unit] =
    throw new UnsupportedOperationException("State must be combined by ArrayElementLengthCheckAggregator.")

  def result(state: State, srvb: StagedRegionValueBuilder, dummy: Boolean): Code[Unit] =
    throw new UnsupportedOperationException("Result must be defined by ArrayElementLengthCheckAggregator.")
}