/* Copyright 2009-2016 EPFL, Lausanne */

package inox
package ast

import scala.collection.BitSet

/** Expression definitions for Pure Scala.
  *
  * Every expression in Inox inherits from [[Expressions.Expr]].
  * Expressions can be manipulated with functions in [[Constructors]] and [[ExprOps]].
  *
  * If you are looking for things such as function or class definitions,
  * please have a look in [[inox.ast.Definitions]].
  *
  * @define encodingof Encoding of
  * @define noteBitvector (32-bit vector)
  * @define noteReal (Real)
  */
trait Expressions { self: Trees =>

  protected def checkParamTypes(real: Seq[Type], formal: Seq[Type], result: Type)(implicit s: Symbols): Type = {
    if (real zip formal forall { case (real, formal) => s.isSubtypeOf(real, formal)} ) {
      result.unveilUntyped
    } else {
      //println(s"Failed to type as $result")
      //println(real map { r => s"$r: ${r.getType}"} mkString ", " )
      //println(formal map { r => s"$r: ${r.getType}" } mkString ", " )
      Untyped
    }
  }

  /** Represents an expression in Inox. */
  abstract class Expr extends Tree with Typed

  /** Trait which gets mixed-in to expressions without subexpressions */
  trait Terminal {
    self: Expr =>
  }


  /** Local assumption
    *
    * @param pred The predicate to be assumed
    * @param body The expression following `assume(pred)`
    */
  sealed case class Assume(pred: Expr, body: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      if (pred.getType == BooleanType()) body.getType
      else Untyped
    }
  }


  /** Variable
    *
    * @param id The identifier of this variable
    */
  sealed case class Variable(id: Identifier, tpe: Type, flags: Set[Flag])
    extends Expr with Terminal with VariableSymbol {

    /** Transforms this [[Variable]] into a [[Definitions.ValDef ValDef]] */
    def toVal = to[ValDef]
    def freshen = copy(id.freshen)

    override def equals(that: Any) = super[VariableSymbol].equals(that)
    override def hashCode = super[VariableSymbol].hashCode

    def copy(id: Identifier = id, tpe: Type = tpe, flags: Set[Flag] = flags) =
      Variable(id, tpe, flags).copiedFrom(this)
  }

  object Variable {
    def fresh(name: String, tpe: Type, alwaysShowUniqueID: Boolean = false) =
      Variable(FreshIdentifier(name, alwaysShowUniqueID), tpe, Set.empty)
  }


  /** $encodingof `val ... = ...; ...`
    *
    * @param vd The ValDef used in body, defined just after '''val'''
    * @param value The value assigned to the identifier, after the '''=''' sign
    * @param body The expression following the ``val ... = ... ;`` construct
    * @see [[SymbolOps.let the let constructor]]
    */
  sealed case class Let(vd: ValDef, value: Expr, body: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      if (s.isSubtypeOf(value.getType, vd.tpe)) body.getType
      else Untyped
    }
  }

  /* Higher-order Functions */

  /** $encodingof `callee(args...)`, where [[callee]] is an expression of a function type (not a method) */
  sealed case class Application(callee: Expr, args: Seq[Expr]) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = callee.getType match {
      case FunctionType(from, to) =>
        checkParamTypes(args.map(_.getType), from, to)
      case _ =>
        Untyped
    }
  }

  /** $encodingof `(args) => body` */
  sealed case class Lambda(args: Seq[ValDef], body: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      FunctionType(args.map(_.getType), body.getType).unveilUntyped

    def paramSubst(realArgs: Seq[Expr]) = {
      require(realArgs.size == args.size)
      (args zip realArgs).toMap
    }

    def withParamSubst(realArgs: Seq[Expr], e: Expr) = {
      exprOps.replaceFromSymbols(paramSubst(realArgs), e)
    }
  }

  /** $encodingof `forall(...)` (universal quantification) */
  sealed case class Forall(args: Seq[ValDef], body: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = body.getType
  }

  /** $encodingof `choose(...)` (returns a value satisfying the provided predicate) */
  sealed case class Choose(res: ValDef, pred: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      if (pred.getType == BooleanType()) res.tpe else Untyped
    }
  }

  /* Control flow */

  /** $encodingof  `function(...)` (function invocation) */
  sealed case class FunctionInvocation(id: Identifier, tps: Seq[Type], args: Seq[Expr])
    extends Expr with CachingTyped {

    override protected def computeType(implicit s: Symbols): Type = s.lookupFunction(id) match {
      case Some(fd) if tps.size == fd.tparams.size && args.size == fd.params.size =>
        val tfd = fd.typed(tps)
        checkParamTypes(args.map(_.getType), tfd.params.map(_.tpe), tfd.returnType)
      case _ => Untyped
    }

    def tfd(implicit s: Symbols): TypedFunDef = s.getFunction(id, tps)

    def inlined(implicit s: Symbols): Expr = {
      val tfd = this.tfd
      exprOps.freshenLocals((tfd.params zip args).foldRight(tfd.fullBody) {
        case ((vd, e), body) => s.let(vd, e, body)
      })
    }
  }

  /** $encodingof `if(...) ... else ...` */
  sealed case class IfExpr(cond: Expr, thenn: Expr, elze: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = 
      s.leastUpperBound(thenn.getType, elze.getType)
  }


  /** Literals */

  sealed abstract class Literal[+T] extends Expr with Terminal {
    val value: T
  }

  /** $encodingof a character literal */
  sealed case class CharLiteral(value: Char) extends Literal[Char] {
    def getType(implicit s: Symbols): Type = CharType()
  }

  /** $encodingof a n-bit bitvector literal */
  sealed case class BVLiteral(value: BitSet, size: Int) extends Literal[BitSet] {
    def getType(implicit s: Symbols): Type = BVType(size)
    def toBigInt: BigInt = {
      val res = value.foldLeft(BigInt(0))((res, i) => res + BigInt(2).pow(i-1))
      if (value(size)) res - BigInt(2).pow(size) else res
    }
  }

  object BVLiteral {
    def apply(bi: BigInt, size: Int): BVLiteral = {
      def extract(bi: BigInt): BitSet = (1 to size).foldLeft(BitSet.empty) {
        case (res, i) => if ((bi & BigInt(2).pow(i-1)) > 0) res + i else res
      }

      val bitSet = if (bi >= 0) extract(bi) else {
        val bs = extract(-bi)
        (1 to size).foldLeft((BitSet.empty, false)) { case ((res, seen1), i) =>
          if (bs(i) && !seen1) (res + i, true)
          else (if (!seen1 || bs(i)) res else res + i, seen1)
        }._1
      }

      BVLiteral(bitSet, size)
    }
  }

  object Int8Literal {
    def apply(x: Byte): BVLiteral = BVLiteral(BigInt(x), 8)
    def unapply(e: Expr): Option[Byte] = e match {
      case b @ BVLiteral(_, 8) => Some(b.toBigInt.toByte)
      case _ => None
    }
  }

  object Int16Literal {
    def apply(x: Short): BVLiteral = BVLiteral(BigInt(x), 16)
    def unapply(e: Expr): Option[Short] = e match {
      case b @ BVLiteral(_, 16) => Some(b.toBigInt.toShort)
      case _ => None
    }
  }

  object Int32Literal {
    def apply(x: Int): BVLiteral = BVLiteral(BigInt(x), 32)
    def unapply(e: Expr): Option[Int] = e match {
      case b @ BVLiteral(_, 32) => Some(b.toBigInt.toInt)
      case _ => None
    }
  }

  object Int64Literal {
    def apply(x: Long): BVLiteral = BVLiteral(BigInt(x), 64)
    def unapply(e: Expr): Option[Long] = e match {
      case b @ BVLiteral(_, 64) => Some(b.toBigInt.toLong)
      case _ => None
    }
  }

  /** $encodingof an infinite precision integer literal */
  sealed case class IntegerLiteral(value: BigInt) extends Literal[BigInt] {
    def getType(implicit s: Symbols): Type = IntegerType()
  }

  /** $encodingof a fraction literal */
  sealed case class FractionLiteral(numerator: BigInt, denominator: BigInt) extends Literal[(BigInt, BigInt)] {
    val value = (numerator, denominator)
    def getType(implicit s: Symbols): Type = RealType()
  }

  /** $encodingof a boolean literal '''true''' or '''false''' */
  sealed case class BooleanLiteral(value: Boolean) extends Literal[Boolean] {
    def getType(implicit s: Symbols): Type = BooleanType()
  }

  /** $encodingof the unit literal `()` */
  sealed case class UnitLiteral() extends Literal[Unit] {
    val value = ()
    def getType(implicit s: Symbols): Type = UnitType()
  }

  /** $encodingof a string literal */
  sealed case class StringLiteral(value: String) extends Literal[String] {
    def getType(implicit s: Symbols): Type = StringType()
  }


  /** Generic values. Represent values of the generic type `tp`.
    * This is useful e.g. to present counterexamples of generic types.
    */
  sealed case class GenericValue(tp: TypeParameter, id: Int) extends Expr with Terminal {
    def getType(implicit s: Symbols): Type = tp
  }


  /** $encodingof `ct(args...)`
    *
    * @param ct The case class name and inherited attributes
    * @param args The arguments of the case class
    */
  sealed case class ADT(id: Identifier, tps: Seq[Type], args: Seq[Expr]) extends Expr with CachingTyped {

    def getConstructor(implicit s: Symbols) = s.getConstructor(id, tps)

    override protected def computeType(implicit s: Symbols): Type =
      s.lookupConstructor(id).flatMap { cons =>
        s.lookupSort(cons.sort)
          .filter(_.tparams.size == tps.size)
          .flatMap { sort =>
            sort.typed(tps).constructors
              .find(_.id == id)
              .filter(_.fields.size == args.size)
              .map(tcons => checkParamTypes(args.map(_.getType), tcons.fieldsTypes, ADTType(sort.id, tps)))
          }
      }.getOrElse(Untyped)
  }

  /** $encodingof `.isInstanceOf[...]` */
  sealed case class IsConstructor(expr: Expr, id: Identifier) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = expr.getType match {
      case ADTType(sort, _) => (s.lookupSort(sort), s.lookupConstructor(id)) match {
        case (Some(sort), Some(cons)) if sort.id == cons.sort => BooleanType()
        case _ => Untyped
      }
      case _ => Untyped
    }
  }

  /** $encodingof `value.selector` where value is of a case class type
    *
    * If you are not sure about the requirement you should use
    * [[SymbolOps.adtSelector the adtSelector constructor]]
    */
  sealed case class ADTSelector(adt: Expr, selector: Identifier) extends Expr with CachingTyped {

    def constructor(implicit s: Symbols) = {
      val tpe = adt.getType.asInstanceOf[ADTType]
      tpe.getSort.constructors.find(_.fields.exists(_.id == selector)).get
    }

    def selectorIndex(implicit s: Symbols) = constructor.definition.selectorID2Index(selector)

    override protected def computeType(implicit s: Symbols): Type = {
      scala.util.Try(constructor.fieldsTypes(selectorIndex)).toOption.getOrElse(Untyped)
    }
  }

  /** $encodingof `... == ...` */
  sealed case class Equals(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      if (s.leastUpperBound(lhs.getType, rhs.getType) != Untyped) BooleanType()
      else {
        //println(s"Incompatible argument types: arguments: ($lhs, $rhs) types: ${lhs.getType}, ${rhs.getType}")
        Untyped
      }
    }
  }


  /* Propositional logic */

  /** $encodingof `... && ...`
    *
    * [[exprs]] must contain at least two elements; if you are not sure about this,
    * you should use [[Constructors.and the and constructor]]
    * or [[Constructors.andJoin the andJoin constructor]]
    */
  sealed case class And(exprs: Seq[Expr]) extends Expr with CachingTyped {
    require(exprs.size >= 2)
    override protected def computeType(implicit s: Symbols): Type = {
      if (exprs forall (_.getType == BooleanType())) BooleanType()
      else Untyped
    }
  }

  object And {
    def apply(a: Expr, b: Expr): Expr = And(Seq(a, b))
  }

  /** $encodingof `... || ...`
    *
    * [[exprs]] must contain at least two elements; if you are not sure about this,
    * you should use [[Constructors#or the or constructor]] or
    * [[Constructors#orJoin the orJoin constructor]]
    */
  sealed case class Or(exprs: Seq[Expr]) extends Expr with CachingTyped {
    require(exprs.size >= 2)
    override protected def computeType(implicit s: Symbols): Type = {
      if (exprs forall (_.getType == BooleanType())) BooleanType()
      else Untyped
    }
  }

  object Or {
    def apply(a: Expr, b: Expr): Expr = Or(Seq(a, b))
  }

  /** $encodingof `... ==> ...` (logical implication).
    *
    * @see [[Constructors.implies]]
    */
  sealed case class Implies(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      if(lhs.getType == BooleanType() && rhs.getType == BooleanType()) BooleanType()
      else Untyped
    }
  }

  /** $encodingof `!...`
    *
    * @see [[Constructors.not]]
    */
  sealed case class Not(expr: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      if (expr.getType == BooleanType()) BooleanType()
      else Untyped
    }
  }


  /* String Theory */

  /** $encodingof `lhs + rhs` for strings */
  sealed case class StringConcat(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      if (lhs.getType == StringType() && rhs.getType == StringType()) StringType()
      else Untyped
    }
  }

  /** $encodingof `lhs.subString(start, end)` for strings */
  sealed case class SubString(expr: Expr, start: Expr, end: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      val ext = expr.getType
      val st = start.getType
      val et = end.getType
      if (ext == StringType() && st == IntegerType() && et == IntegerType()) StringType()
      else Untyped
    }
  }

  /** $encodingof `lhs.length` for strings */
  sealed case class StringLength(expr: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      if (expr.getType == StringType()) IntegerType()
      else Untyped
    }
  }

  /* General arithmetic */

  def numericType(tpe: Type, tpes: Type*)(implicit s: Symbols): Type = {
    lazy val intType = integerType(tpe, tpes : _*)
    lazy val bvType = bitVectorType(tpe, tpes : _*)
    lazy val rlType = realType(tpe, tpes : _*)
    if (intType.isTyped) intType else if (bvType.isTyped) bvType else rlType
  }

  def integerType(tpe: Type, tpes: Type*)(implicit s: Symbols): Type = tpe match {
    case IntegerType() if tpes.forall(tpe == _) => tpe
    case _ => Untyped
  }

  def bitVectorType(tpe: Type, tpes: Type*)(implicit s: Symbols): Type = tpe match {
    case _: BVType if tpes.forall(tpe == _) => tpe
    case _ => Untyped
  }

  def realType(tpe: Type, tpes: Type*)(implicit s: Symbols): Type = tpe match {
    case RealType() if tpes.forall(tpe == _) => tpe
    case _ => Untyped
  }

  /** $encodingof `... +  ...` */
  sealed case class Plus(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = numericType(lhs.getType, rhs.getType)
  }

  /** $encodingof `... -  ...` */
  sealed case class Minus(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = numericType(lhs.getType, rhs.getType)
  }

  /** $encodingof `- ...` */
  sealed case class UMinus(expr: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = numericType(expr.getType)
  }

  /** $encodingof `... * ...` */
  sealed case class Times(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = numericType(lhs.getType, rhs.getType)
  }

  /** $encodingof `... /  ...`
    *
    * Division and Remainder follows Java/Scala semantics. Division corresponds
    * to / operator on BigInt and Remainder corresponds to %. Note that in
    * Java/Scala % is called remainder and the "mod" operator (Modulo in Inox) is also
    * defined on BigInteger and differs from Remainder. The "mod" operator
    * returns an always positive remainder, while Remainder could return
    * a negative remainder. The following must hold:
    *
    *    Division(x, y) * y + Remainder(x, y) == x
    */
  sealed case class Division(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = numericType(lhs.getType, rhs.getType)
  }

  /** $encodingof `... %  ...` (can return negative numbers)
    *
    * @see [[Expressions.Division]]
    */
  sealed case class Remainder(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = integerType(lhs.getType, rhs.getType) match {
      case Untyped => bitVectorType(lhs.getType, rhs.getType)
      case tpe => tpe
    }
  }

  /** $encodingof `... mod  ...` (cannot return negative numbers)
    *
    * @see [[Expressions.Division]]
    */
  sealed case class Modulo(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = integerType(lhs.getType, rhs.getType) match {
      case Untyped => bitVectorType(lhs.getType, rhs.getType)
      case tpe => tpe
    }
  }

  /** $encodingof `... < ...`*/
  sealed case class LessThan(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      if (numericType(lhs.getType, rhs.getType) != Untyped) BooleanType()
      else if (lhs.getType == CharType() && rhs.getType == CharType()) BooleanType()
      else Untyped
  }

  /** $encodingof `... > ...`*/
  sealed case class GreaterThan(lhs: Expr, rhs: Expr) extends Expr with CachingTyped{
    override protected def computeType(implicit s: Symbols): Type =
      if (numericType(lhs.getType, rhs.getType) != Untyped) BooleanType()
      else if (lhs.getType == CharType() && rhs.getType == CharType()) BooleanType()
      else Untyped
  }

  /** $encodingof `... <= ...`*/
  sealed case class LessEquals(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      if (numericType(lhs.getType, rhs.getType) != Untyped) BooleanType()
      else if (lhs.getType == CharType() && rhs.getType == CharType()) BooleanType()
      else Untyped
  }

  /** $encodingof `... >= ...`*/
  sealed case class GreaterEquals(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      if (numericType(lhs.getType, rhs.getType) != Untyped) BooleanType()
      else if (lhs.getType == CharType() && rhs.getType == CharType()) BooleanType()
      else Untyped
  }


  /* Bit-vector operations */

  /** $encodingof `~...` $noteBitvector */
  sealed case class BVNot(e: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = bitVectorType(e.getType)
  }

  /** $encodingof `... | ...` $noteBitvector */
  sealed case class BVOr(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = bitVectorType(lhs.getType, rhs.getType)
  }

  /** $encodingof `... & ...` $noteBitvector */
  sealed case class BVAnd(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = bitVectorType(lhs.getType, rhs.getType)
  }

  /** $encodingof `... ^ ...` $noteBitvector */
  sealed case class BVXor(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = bitVectorType(lhs.getType, rhs.getType)
  }

  /** $encodingof `... << ...` $noteBitvector */
  sealed case class BVShiftLeft(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = bitVectorType(lhs.getType, rhs.getType)
  }

  /** $encodingof `... >> ...` $noteBitvector (arithmetic shift, sign-preserving) */
  sealed case class BVAShiftRight(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = bitVectorType(lhs.getType, rhs.getType)
  }

  /** $encodingof `... >>> ...` $noteBitvector (logical shift) */
  sealed case class BVLShiftRight(lhs: Expr, rhs: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = bitVectorType(lhs.getType, rhs.getType)
  }

  /** $encodingof `... .toByte` and other narrowing casts */
  sealed case class BVNarrowingCast(expr: Expr, newType: BVType) extends Expr with CachingTyped {
    // The expression is well types iff `expr` is well typed and the BVTypes' size match a narrowing cast.
    override protected def computeType(implicit s: Symbols): Type = cast match {
      case Some((from, to)) => newType
      case _ => Untyped
    }

    // Returns the pair of sizes from -> to
    def cast(implicit s: Symbols): Option[(Int, Int)] = expr.getType match {
      case BVType(from) if from > newType.size => Some(from -> newType.size)
      case _ => None
    }
  }

  /** $encodingof `... .toInt` and other widening casts */
  sealed case class BVWideningCast(expr: Expr, newType: BVType) extends Expr with CachingTyped {
    // The expression is well types iff `expr` is well typed and the BVTypes' size match a widening cast.
    override protected def computeType(implicit s: Symbols): Type = cast match {
      case Some((from, to)) => newType
      case _ => Untyped
    }

    // Returns the pair of sizes from -> to
    def cast(implicit s: Symbols): Option[(Int, Int)] = expr.getType match {
      case BVType(from) if from < newType.size => Some(from -> newType.size)
      case _ => None
    }
  }


  /* Tuple operations */

  /** $encodingof `(..., ....)` (tuple)
    *
    * [[exprs]] should always contain at least 2 elements.
    * If you are not sure about this requirement, you should use
    * [[Constructors.tupleWrap the tupleWrap constructor]]
    *
    * @param exprs The expressions in the tuple
    */
  sealed case class Tuple(exprs: Seq[Expr]) extends Expr with CachingTyped {
    require(exprs.size >= 2)
    override protected def computeType(implicit s: Symbols): Type = {
      TupleType(exprs.map(_.getType)).unveilUntyped
    }
  }

  /** $encodingof `(tuple)._i`
    *
    * Index is 1-based, first element of tuple is 1.
    * If you are not sure that [[tuple]] is indeed of a TupleType, you should use one of the
    * [[SymbolOps.tupleSelect(t:SymbolOps\.this\.trees\.Expr,index:Int,originalSize:Int)*]]
    * [[SymbolOps.tupleSelect(t:SymbolOps\.this\.trees\.Expr,index:Int,isTuple:Boolean)*]]
    * constructors
    */
  sealed case class TupleSelect(tuple: Expr, index: Int) extends Expr with CachingTyped {
    require(index >= 1)

    override protected def computeType(implicit s: Symbols): Type = tuple.getType match {
      case tp @ TupleType(ts) =>
        require(index <= ts.size, s"Got index $index for '$tuple' of type '$tp")
        ts(index - 1)
      case _ => Untyped
    }
  }


  /* Set operations */

  /** $encodingof `Set[base](elements)` */
  sealed case class FiniteSet(elements: Seq[Expr], base: Type) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = SetType(
      checkParamTypes(elements.map(_.getType), List.fill(elements.size)(base), base)
    ).unveilUntyped
  }

  /** $encodingof `set + elem` */
  sealed case class SetAdd(set: Expr, elem: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      val base = set.getType match {
        case SetType(base) => base
        case _ => Untyped
      }
      checkParamTypes(Seq(elem.getType), Seq(base), SetType(base).unveilUntyped)
    }
  }

  /** $encodingof `set.contains(element)` or `set(element)` */
  sealed case class ElementOfSet(element: Expr, set: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols) =
      checkParamTypes(Seq(element.getType), Seq(set.getType match {
        case SetType(base) => base
        case _ => Untyped
      }), BooleanType())
  }

  /** $encodingof `set.subsetOf(set2)` */
  sealed case class SubsetOf(set1: Expr, set2: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      (set1.getType, set2.getType) match {
        case (SetType(b1), SetType(b2)) if b1 == b2 => BooleanType()
        case _ => Untyped
      }
  }

  /** $encodingof `set & set2` */
  sealed case class SetIntersection(set1: Expr, set2: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      s.leastUpperBound(Seq(set1, set2).map(_.getType))
  }

  /** $encodingof `set ++ set2` */
  sealed case class SetUnion(set1: Expr, set2: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      s.leastUpperBound(Seq(set1, set2).map(_.getType))
  }

  /** $encodingof `set -- set2` */
  sealed case class SetDifference(set1: Expr, set2: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      s.leastUpperBound(Seq(set1, set2).map(_.getType))
  }


  /* Bag operations */

  /** $encodingof `Bag[base](elements)` */
  sealed case class FiniteBag(elements: Seq[(Expr, Expr)], base: Type) extends Expr with CachingTyped {
    private[this] val tpe = BagType(base).unveilUntyped
    override protected def computeType(implicit s: Symbols): Type = {
      checkParamTypes(
        elements.map(_._1.getType) ++ elements.map(_._2.getType),
        List.fill(elements.size)(base) ++ List.fill(elements.size)(IntegerType()), tpe)
    }
  }

  /** $encodingof `bag + elem` */
  sealed case class BagAdd(bag: Expr, elem: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = {
      val base = bag.getType match {
        case BagType(base) => base
        case _ => Untyped
      }
      checkParamTypes(Seq(elem.getType), Seq(base), BagType(base).unveilUntyped)
    }
  }

  /** $encodingof `bag.get(element)` or `bag(element)` */
  sealed case class MultiplicityInBag(element: Expr, bag: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      checkParamTypes(Seq(element.getType), Seq(bag.getType match {
        case BagType(base) => base
        case _ => Untyped
      }), IntegerType())
  }

  /** $encodingof `bag1 & bag2` */
  sealed case class BagIntersection(bag1: Expr, bag2: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      s.leastUpperBound(Seq(bag1, bag2).map(_.getType))
  }

  /** $encodingof `bag1 ++ bag2` */
  sealed case class BagUnion(bag1: Expr, bag2: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      s.leastUpperBound(Seq(bag1, bag2).map(_.getType))
  }

  /** $encodingof `bag1 -- bag2` */
  sealed case class BagDifference(bag1: Expr, bag2: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type =
      s.leastUpperBound(Seq(bag1, bag2).map(_.getType))
  }


  /* Total map operations */

  /** $encodingof `Map[keyType, valueType](key1 -> value1, key2 -> value2 ...)` */
  sealed case class FiniteMap(pairs: Seq[(Expr, Expr)], default: Expr, keyType: Type, valueType: Type)
    extends Expr with CachingTyped {

    override protected def computeType(implicit s: Symbols): Type = MapType(
      checkParamTypes(pairs.map(_._1.getType), List.fill(pairs.size)(keyType), keyType),
      checkParamTypes(pairs.map(_._2.getType) :+ default.getType, List.fill(pairs.size + 1)(valueType), valueType)
    ).unveilUntyped
  }

  /** $encodingof `map.apply(key)` (or `map(key)`) */
  sealed case class MapApply(map: Expr, key: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols): Type = map.getType match {
      case MapType(from, to) => checkParamTypes(Seq(key.getType), Seq(from), to)
      case _ => Untyped
    }
  }

  /** $encodingof `map.updated(key, value)` (or `map + (key -> value)`) */
  sealed case class MapUpdated(map: Expr, key: Expr, value: Expr) extends Expr with CachingTyped {
    override protected def computeType(implicit s: Symbols) = map.getType match {
      case mt@MapType(from, to) => checkParamTypes(Seq(key.getType, value.getType), Seq(from, to), mt)
      case _ => Untyped
    }
  }
}
