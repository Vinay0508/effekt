package effekt
package core

import effekt.typer.Typer
import effekt.symbols._
import effekt.symbols.builtins._
import effekt.util.control
import effekt.util.control._
import org.bitbucket.inkytonik.kiama.util.{ Memoiser }

object Wildcard extends Symbol { val name = LocalName("_") }

class Transformer {

  given Assertions

  def run(unit: CompilationUnit): ModuleDecl = {
    val source.ModuleDecl(path, imports, defs) = unit.module
    val exports: Stmt = Exports(path, unit.exports.terms.collect {
      case (name, sym) if sym.isInstanceOf[Fun] && !sym.isInstanceOf[EffectOp] => sym
    }.toList)

    val ctx = Context(unit)

    ModuleDecl(path, imports.map { _.path }, defs.foldRight(exports) { case (d, r) =>
      transform(d, r)(given ctx)
    })
  }

  def transform(d: source.Def, rest: Stmt)(given Context): Stmt = d match {
    case source.FunDef(id, _, params, _, body) =>
      val sym = id.symbol.asInstanceOf[Fun]
      val effs = sym.effects

      val ps = params.flatMap {
        case source.BlockParam(id, _) => List(core.BlockParam(id.symbol))
        case source.ValueParams(ps) => ps.map { p => core.ValueParam(p.id.symbol) }
      } ++ effs.filterNot(_.builtin).map { core.BlockParam }

      Def(sym, BlockDef(ps, transform(body)), rest)

    case source.DataDef(id, _, ctors) =>
      Data(id.symbol, ctors.map { c => c.id.symbol }, rest)

    case source.ValDef(id, _, binding) =>
      Val(id.symbol, transform(binding), rest)

    case source.VarDef(id, _, binding) =>
      Var(id.symbol, transform(binding), rest)

    case source.ExternType(id, tparams) =>
      rest

    case source.ExternFun(pure, id, tparams, params, ret, body) =>
      // C&P from FunDef
      val effs = id.symbol.asInstanceOf[Fun].effects
      val ps = params.flatMap {
        case source.BlockParam(id, _) => List(core.BlockParam(id.symbol))
        case source.ValueParams(ps) => ps.map { p => core.ValueParam(p.id.symbol) }
      } ++ effs.filterNot(_.builtin).map { core.BlockParam }
      Def(id.symbol, Extern(ps, body), rest)

    case e @ source.ExternInclude(path) =>
      Include(e.contents, rest)

    case e: source.ExternEffect =>
      rest

    case e: source.EffDef =>
      rest
  }

  def transform(tree: source.Stmt)(given Context): Stmt = tree match {
    case source.DefStmt(d, rest) =>
      transform(d, transform(rest))

    case source.ExprStmt(e, rest) =>
      Val(Wildcard, ANF { transform(e).map(Ret) }, transform(rest))

    case source.Return(e) =>
      ANF { transform(e).map(Ret) }
  }

  def transform(tree: source.Expr)(given Context): Control[Expr] = tree match {
    case source.Var(id) => id.symbol match {
      case sym: VarBinder => pure { Deref(sym) }
      case sym => pure { ValueVar(sym) }
    }

    case source.Assign(id, expr) =>
      transform(expr).map { e => Assign(id.symbol, e) }

    case source.UnitLit() => pure { UnitLit() }
    case source.IntLit(value) => pure { IntLit(value) }
    case source.BooleanLit(value) => pure { BooleanLit(value) }
    case source.DoubleLit(value) => pure { DoubleLit(value) }
    case source.StringLit(value) => pure { StringLit(value) }

    case source.If(cond, thn, els) =>
      transform(cond).flatMap { c => bind(If(c, transform(thn), transform(els))) }

    case source.While(cond, body) =>
      bind(While(ANF { transform(cond) map Ret }, transform(body)))

    case source.MatchExpr(sc, clauses) =>
      val cs = clauses.map {
        case source.Clause(op, source.ValueParams(params), body) =>
          val ps = params.map { v => core.ValueParam(v.id.symbol) }
          (op.symbol, BlockDef(ps, transform(body)))
      }
      transform(sc).flatMap { scrutinee => bind(Match(scrutinee, cs)) }

    case source.Call(fun, _, args) =>
      val sym = fun.symbol.asInstanceOf[Fun]
      val effs = sym.effects
      val capabilities = effs.filterNot(_.builtin).map { BlockVar }

      val as: List[Control[List[Expr | Block]]] = (args zip sym.params) map {
        case (source.ValueArgs(as), _) => traverse(as.map(transform))
        case (source.BlockArg(ps, body), p: BlockParam) =>
          val params = ps.map { v => core.ValueParam(v.id.symbol) }
          val caps = p.tpe.ret.effects.effs.filterNot(_.builtin).map { core.BlockParam }
          pure { List(BlockDef(params ++ caps, transform(body))) }
      }

      val as2: Control[List[Expr | Block]] = traverse(as).map { ls => ls.flatMap(identity) }

      // right now only builtin functions are pure of control effects
      // later we can have effect inference to learn which ones are pure.
      if ((sym.builtin && sym.asBuiltinFunction.pure) || sym.isInstanceOf[Constructor]) {
        as2.map { args => PureApp(BlockVar(sym), args ++ capabilities) }
      } else {
        as2.flatMap { args => bind(App(BlockVar(sym), args ++ capabilities)) }
      }

    case source.Yield(id, source.ValueArgs(as)) =>
      val sym = id.symbol.asInstanceOf[BlockParam]
      val capabilities = sym.tpe.ret.effects.effs.filterNot(_.builtin).map { BlockVar }
      traverse(as.map(transform)).flatMap { args => bind(App(BlockVar(id.symbol), args ++ capabilities)) }

    case source.Do(op, _, source.ValueArgs(as)) =>
      traverse(as.map(transform)).flatMap { args => bind(App(BlockVar(op.symbol), args)) }

    case source.Resume(source.ValueArgs(as)) =>
      traverse(as.map(transform)).flatMap { args => bind(App(BlockVar(ResumeParam), args)) }

    case source.TryHandle(prog, clauses) =>
      val capabilities = clauses.map { c => core.BlockParam(c.op.symbol) }
      val body = BlockDef(capabilities, transform(prog))
      val cs = clauses.map {
        case source.Clause(op, source.ValueParams(params), body) =>
          val ps = params.map { v => core.ValueParam(v.id.symbol) } :+ core.BlockParam(ResumeParam)
          (op.symbol, BlockDef(ps, transform(body)))
      }
      bind(Handle(body, cs))
  }

  def traverse[R](ar: List[Control[R]])(given Context): Control[List[R]] = ar match {
    case Nil => pure { Nil }
    case (r :: rs) => for {
      rv <- r
      rsv <- traverse(rs)
    } yield rv :: rsv
  }

  def transform(exprs: List[source.Expr])(given Context): Control[List[Expr]] = exprs match {
    case Nil => pure { Nil }
    case (e :: rest) => for {
      ev <- transform(e)
      rv <- transform(rest)
    } yield ev :: rv
  }

  case class Context(unit: CompilationUnit) {
    def (f: Fun) effects = (f.ret match {
      case Some(t) => t
      case None => unit.types(f)
    }).effects.effs

    def (id: source.Id) symbol = unit.symbols(id)
  }
  def Context(given c: Context): Context = c

  private val delimiter: Cap[Stmt] = new Capability { type Res = Stmt }
  case class Tmp() extends Symbol { val name = LocalName("tmp" + Symbol.fresh.next()) }
  def ANF(e: Control[Stmt]): Stmt = control.handle(delimiter)(e).run()
  def bind(e: Stmt): Control[Expr] = control.use(delimiter) { k =>
    val x = Tmp()
    k.apply(ValueVar(x)).map {
      case Ret(ValueVar(y)) if x == y => e
      case body => Val(x, e, body)
    }
  }
}