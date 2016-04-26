/* Copyright 2009-2016 EPFL, Lausanne */

package leon
package repair
package rules

import synthesis._
import leon.evaluators._

import purescala.Path
import purescala.Expressions._
import purescala.Common._
import purescala.Types._
import purescala.ExprOps._
import purescala.Constructors._
import purescala.Extractors._

import Witnesses._

import graph.AndNode

case object Focus extends PreprocessingRule("Focus") {

  def instantiateOn(implicit hctx: SearchContext, p: Problem): Traversable[RuleInstantiation] = {
    hctx.parentNode match {
      case Some(an: AndNode) if an.ri.rule == Focus =>
        // We proceed as usual
      case Some(_) =>
        return None;
      case _ =>
        
    }

    val fd      = hctx.functionContext
    val program = hctx.program

    val evaluator = new DefaultEvaluator(hctx, program)

    // Check how an expression behaves on tests
    //  - returns Some(true) if for all tests e evaluates to true
    //  - returns Some(false) if for all tests e evaluates to false
    //  - returns None otherwise
    def forAllTests(e: Expr, env: Map[Identifier, Expr], evaluator: Evaluator): Option[Boolean] = {
      var soFar: Option[Boolean] = None

      p.eb.invalids.foreach { ex =>
        evaluator.eval(e, (p.as zip ex.ins).toMap ++ env) match {
          case EvaluationResults.Successful(BooleanLiteral(b)) => 
            soFar match {
              case None =>
                soFar = Some(b)
              case Some(`b`) =>
                /* noop */
              case _ => 
                return None
            }

          case e =>
            //println("Evaluator said "+e)
            return None
        }
      }

      soFar
    }

    def existsFailing(e: Expr, env: Map[Identifier, Expr], evaluator: DeterministicEvaluator): Boolean = {
      p.eb.invalids.exists { ex =>
        evaluator.evalEnvExpr(e, (p.as zip ex.ins).toMap ++ env).result match {
          case Some(BooleanLiteral(b)) => b
          case _ => true
        }
      }
    }

    val fdSpec = {
      val id = FreshIdentifier("res", fd.returnType)
      Let(id, fd.body.get, application(fd.postOrTrue, Seq(id.toVariable)))
    }

    val TopLevelAnds(clauses) = p.ws

    val guides = clauses.collect {
      case Guide(expr) => expr
    }

    val wss = clauses.filter {
      case _: Guide => false
      case _ => true
    }

    def ws(g: Expr) = andJoin(Guide(g) +: wss)

    def testCondition(cond: Expr) = {
      val ndSpec = postMap {
        case c if c eq cond => Some(not(cond))
        case _ => None
      }(fdSpec)
      forAllTests(ndSpec, Map(), new AngelicEvaluator(new RepairNDEvaluator(hctx, program, cond)))
    }

    guides.flatMap {
      case g @ IfExpr(c, thn, els) =>
        testCondition(c) match {
          case Some(true) =>
            val cx = FreshIdentifier("cond", BooleanType)
            // Focus on condition
            val np = Problem(p.as, ws(c), p.pc, letTuple(p.xs, IfExpr(cx.toVariable, thn, els), p.phi), List(cx), p.eb.stripOuts)

            Some(decomp(List(np), termWrap(IfExpr(_, thn, els)), s"Focus on if-cond '${c.asString}'")(p))

          case _ =>
            // Try to focus on branches
            forAllTests(c, Map(), evaluator) match {
              case Some(true) =>
                val np = Problem(p.as, ws(thn), p.pc withCond c, p.phi, p.xs, p.qeb.filterIns(c))

                Some(decomp(List(np), termWrap(IfExpr(c, _, els), c), s"Focus on if-then")(p))
              case Some(false) =>
                val np = Problem(p.as, ws(els), p.pc withCond not(c), p.phi, p.xs, p.qeb.filterIns(not(c)))

                Some(decomp(List(np), termWrap(IfExpr(c, thn, _), not(c)), s"Focus on if-else")(p))
              case None =>
                // We split
                val sub1 = p.copy(ws = ws(thn), pc = p.pc map (replace(Map(g -> thn), _)) withCond     c , eb = p.qeb.filterIns(c))
                val sub2 = p.copy(ws = ws(els), pc = p.pc map (replace(Map(g -> thn), _)) withCond Not(c), eb = p.qeb.filterIns(Not(c)))

                val onSuccess: List[Solution] => Option[Solution] = { 
                  case List(s1, s2) =>
                    Some(Solution(or(s1.pre, s2.pre), s1.defs++s2.defs, IfExpr(c, s1.term, s2.term)))
                  case _ =>
                    None
                }

                Some(decomp(List(sub1, sub2), onSuccess, s"Focus on both branches of '${c.asString}'"))
            }
        }

      case MatchExpr(scrut, cases) =>
        var pcSoFar = Path.empty

        // Generate subproblems for each match-case that fails at least one test.
        var casesInfos = for (c <- cases) yield {
          val map  = mapForPattern(scrut, c.pattern)

          val thisCond = matchCaseCondition(scrut, c)
          val cond = pcSoFar merge thisCond
          pcSoFar = pcSoFar merge thisCond.negate

          val subP = if (existsFailing(cond.toClause, map, evaluator)) {
            val vars = map.toSeq.map(_._1)

            // Filter tests by the path-condition
            val eb2 = p.qeb.filterIns(cond.toClause)

            // Augment test with the additional variables and their valuations
            val ebF: (Seq[Expr] => List[Seq[Expr]]) = { (e: Seq[Expr]) =>
              val emap = (p.as zip e).toMap

              evaluator.eval(tupleWrap(vars.map(map)), emap).result.map { r =>
                e ++ unwrapTuple(r, vars.size)
              }.toList
            }

            val eb3 = if (vars.nonEmpty) {
              eb2.mapIns(ebF)
            } else {
              eb2.eb
            }

            val newPc = Path.empty withBindings vars.map(id => id -> map(id)).toSeq merge cond

            Some(Problem(p.as, ws(c.rhs), p.pc merge newPc, p.phi, p.xs, eb3))
          } else {
            None
          }

          c -> (subP, cond)
        }

        // Check if the match might be missing a case? (we check if one test
        // goes to no defined cases)
        val elsePc = pcSoFar

        if (existsFailing(elsePc.toClause, Map(), evaluator)) {
          val newCase    = MatchCase(WildcardPattern(None), None, NoTree(scrut.getType))

          val eb = p.qeb.filterIns(elsePc.toClause)

          val newProblem = Problem(p.as, andJoin(wss), p.pc merge elsePc, p.phi, p.xs, eb)

          casesInfos :+= (newCase -> (Some(newProblem), elsePc))
        }

        // Is there at least one subproblem?
        if (casesInfos.exists(_._2._1.isDefined)) {
          val infosP = casesInfos.collect {
            case (c, (Some(p), pc)) => (c, (p, pc))
          }

          val nps = infosP.map(_._2._1).toList

          val appName = s"Focus on match-cases ${infosP.map(i => "'"+i._1.pattern.asString+"'").mkString(", ")}"

          val onSuccess: List[Solution] => Option[Solution] = { 
            case ss =>
              val matchSols = (infosP zip ss).map { case ((c, (pc)), s) => (c, (pc, s)) }

              val pres = matchSols.map {
                case (_, (pc, s)) =>
                  if(s.pre == BooleanLiteral(true)) {
                    BooleanLiteral(true)
                  } else {
                    p.pc and s.pre
                  }
              }

              val solsMap = matchSols.toMap

              val expr = MatchExpr(scrut, casesInfos.map { case (c, _) => solsMap.get(c) match {
                case Some((pc, s)) =>
                  c.copy(rhs = s.term)
                case None =>
                  c
              }})

              Some(Solution(orJoin(pres), ss.map(_.defs).reduceLeft(_ ++ _), expr))
          }

          Some(decomp(nps, onSuccess, appName)(p))
        } else {
          None
        }


      case Let(id, value, body) =>
        val ebF: (Seq[Expr] => List[Seq[Expr]]) = { (e: Seq[Expr]) =>
          val map = (p.as zip e).toMap

          evaluator.eval(value, map).result.map { r =>
            e :+ r
          }.toList
        }

        val np = Problem(p.as, ws(body), p.pc withBinding (id -> value), p.phi, p.xs, p.eb.mapIns(ebF))

        Some(decomp(List(np), termWrap(Let(id, value, _)), s"Focus on let-body")(p))

      case _ => None
    }
  }
}
