/* Copyright 2009-2016 EPFL, Lausanne */

package inox
package solvers
package unrolling

import utils._

import scala.collection.mutable.{Set => MutableSet, Map => MutableMap}

/** Incrementally unfolds equality relations between types for which the
  * SMT notion of equality is not relevant.
  *
  * @see [[ast.Definitions.ADTDefinition.equality]] for such a case of equality
  */
trait EqualityTemplates { self: Templates =>
  import program._
  import program.trees._
  import program.symbols._

  import equalityManager._

  private val checking: MutableSet[TypedADTDefinition] = MutableSet.empty
  private val unrollCache: MutableMap[Type, Boolean] = MutableMap.empty

  def unrollEquality(tpe: Type): Boolean = unrollCache.getOrElseUpdate(tpe, tpe match {
    case adt: ADTType =>
      val root = adt.getADT.root
      root.hasEquality || (!checking(root) && {
        checking += root
        val constructors = root match {
          case tsort: TypedADTSort => tsort.constructors
          case tcons: TypedADTConstructor => Seq(tcons)
        }

        constructors.exists(c => c.fieldsTypes.exists(unrollEquality))
      })

    case BooleanType | UnitType | CharType | IntegerType |
         RealType | StringType | (_: BVType) | (_: TypeParameter) => false

    case NAryType(tpes, _) => tpes.exists(unrollEquality)
  })

  def equalitySymbol(tpe: Type): (Variable, Encoded) = typeSymbols.cached(tpe) {
    val v = Variable(FreshIdentifier("eq" + tpe), FunctionType(Seq(tpe, tpe), BooleanType))
    v -> encodeSymbol(v)
  }

  class EqualityTemplate private(val contents: TemplateContents) extends Template {
    def instantiate(aVar: Encoded, e1: Encoded, e2: Encoded): Clauses = {
      instantiate(aVar, Seq(Left(e1), Left(e2)))
    }

    override protected def instantiate(substMap: Map[Encoded, Arg]): Clauses = {
      val clauses = Template.instantiate(
        contents.clauses, contents.blockers, contents.applications, contents.matchers,
        Map.empty, substMap)

      // register equalities!
      val substituter = mkSubstituter(substMap.mapValues(_.encoded))
      for ((b, eqs) <- contents.equalities; bp = substituter(b); equality <- eqs) {
        val seq = equality.substitute(substituter)
        val gen = nextGeneration(currentGeneration)
        val notBp = mkNot(bp)

        equalityInfos.get(bp) match {
          case Some((exGen, origGen, _, exEqs)) =>
            val minGen = gen min exGen
            equalityInfos += bp -> (minGen, origGen, notBp, exEqs + seq)
          case None =>
            equalityInfos += bp -> (gen, gen, notBp, Set(seq))
        }
      }

      clauses
    }
  }

  object EqualityTemplate {
    private val cache: MutableMap[Type, EqualityTemplate] = MutableMap.empty

    def apply(tpe: Type): EqualityTemplate = cache.getOrElseUpdate(tpe, {
      val (f, fT) = equalitySymbol(tpe)
      val args @ Seq(e1, e2) = Seq("e1", "e2").map(s => Variable(FreshIdentifier(s), tpe))
      val argsT = args.map(encodeSymbol)

      val pathVar = Variable(FreshIdentifier("b", true), BooleanType)
      val pathVarT = encodeSymbol(pathVar)

      val (condVars, exprVars, condTree, guardedExprs, equations, lambdas, quantifications) =
        mkClauses(pathVar, Equals(Application(f, args), tpe match {
          case adt: ADTType =>
            val root = adt.getADT.root

            if (root.hasEquality) {
              root.equality.get.applied(args)
            } else {
              val constructors = root match {
                case tsort: TypedADTSort => tsort.constructors
                case tcons: TypedADTConstructor => Seq(tcons)
              }

              orJoin(constructors.map { tcons =>
                val (instCond, asE1, asE2) = if (tcons == root) (BooleanLiteral(true), e1, e2) else (
                  and(IsInstanceOf(e1, tcons.toType), IsInstanceOf(e2, tcons.toType)),
                  AsInstanceOf(e1, tcons.toType),
                  AsInstanceOf(e2, tcons.toType)
                )

                val fieldConds = tcons.fields.map(vd => Equals(ADTSelector(asE1, vd.id), ADTSelector(asE2, vd.id)))
                andJoin(instCond +: fieldConds)
              })
            }

          case TupleType(tps) =>
            andJoin(tps.indices.map(i => Equals(TupleSelect(e1, i + 1), TupleSelect(e2, i + 1))))

          case _ => throw FatalError(s"Why does $tpe require equality unrolling!?")
        }), (args zip argsT).toMap + (f -> fT) + (pathVar -> encodeSymbol(pathVar)))

      val (contents, _) = Template.contents(
        pathVar -> pathVarT, args zip argsT, condVars, exprVars, condTree,
        guardedExprs, equations, lambdas, quantifications,
        substMap = Map(f -> fT),
        optApp = Some(fT -> FunctionType(Seq(tpe, tpe), BooleanType))
      )

      new EqualityTemplate(contents)
    })
  }

  def instantiateEquality(blocker: Encoded, equality: Equality): Clauses = {
    EqualityTemplate(equality.tpe).instantiate(blocker, equality.e1, equality.e2)
  }

  def registerEquality(blocker: Encoded, tpe: Type, e1: Encoded, e2: Encoded): Encoded = {
    registerEquality(blocker, Equality(tpe, e1, e2))
  }

  def registerEquality(blocker: Encoded, equality: Equality): Encoded = {
    val tpe = equality.tpe
    val gen = nextGeneration(currentGeneration)
    val notBlocker = mkNot(blocker)

    equalityInfos.get(blocker) match {
      case Some((exGen, origGen, _, exEqs)) =>
        val minGen = gen min exGen
        equalityInfos += blocker -> (minGen, origGen, notBlocker, exEqs + equality)
      case None =>
        equalityInfos += blocker -> (gen, gen, notBlocker, Set(equality))
    }

    mkApp(equalitySymbol(tpe)._2, FunctionType(Seq(tpe, tpe), BooleanType), Seq(equality.e1, equality.e2))
  }

  private[unrolling] object equalityManager extends Manager {
    private[EqualityTemplates] val typeSymbols = new IncrementalMap[Type, (Variable, Encoded)]
    private[EqualityTemplates] val equalityInfos = new IncrementalMap[Encoded, (Int, Int, Encoded, Set[Equality])]

    val incrementals: Seq[IncrementalState] = Seq(typeSymbols, equalityInfos)

    def unrollGeneration: Option[Int] =
      if (equalityInfos.isEmpty) None
      else Some(equalityInfos.values.map(_._1).min)

    def satisfactionAssumptions: Seq[Encoded] = equalityInfos.map(_._2._3).toSeq
    def refutationAssumptions: Seq[Encoded] = Seq.empty

    def promoteBlocker(b: Encoded): Boolean = {
      if (equalityInfos contains b) {
        val (_, origGen, notB, eqs) = equalityInfos(b)
        equalityInfos += b -> (currentGeneration, origGen, notB, eqs)
        true
      } else {
        false
      }
    }

    def unroll: Clauses = if (equalityInfos.isEmpty) Seq.empty else {
      val newClauses = new scala.collection.mutable.ListBuffer[Encoded]

      val eqBlockers = equalityInfos.filter(_._2._1 <= currentGeneration).toSeq.map(_._1)
      val newEqInfos = eqBlockers.flatMap(id => equalityInfos.get(id).map(id -> _))
      equalityInfos --= eqBlockers

      for ((blocker, (gen, _, _, eqs)) <- newEqInfos; Equality(tpe, e1, e2) <- eqs) {
        newClauses ++= EqualityTemplate(tpe).instantiate(blocker, e1, e2)
      }

      ctx.reporter.debug("Unrolling equalities (" + newClauses.size + ")")
      for (cl <- newClauses) {
        ctx.reporter.debug("  . " + cl)
      }

      newClauses.toSeq
    }
  }
}
