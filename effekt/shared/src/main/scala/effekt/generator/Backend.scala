package effekt
package generator

import effekt.context.Context
import effekt.symbols.{ Module, TermSymbol }

import kiama.output.PrettyPrinterTypes.Document
import kiama.util.Source

trait BackendPhase {

  /**
   * A Unix path that is *not* platform dependent.
   */
  def path(m: Module)(implicit C: Context): String

  /**
   * Entrypoint used by REPL and Driver to compile a file and execute it
   */
  def whole: Phase[CoreTransformed, Compiled]

  /**
   * Entrypoint used by the LSP server to show the compiled output
   */
  def separate: Phase[AllTransformed, (CoreTransformed, Document)]
}

/**
 * As long as phases are not used *within* a backend, it is easier to
 * implement abstract methods.
 */
trait Backend extends BackendPhase {

  /**
   * Entrypoint used by REPL and Driver to compile a file and execute it.
   */
  def compileWhole(main: CoreTransformed, mainSymbol: TermSymbol)(using Context): Option[Compiled]

  /**
   * Entrypoint used by the LSP server to show the compiled output
   */
  def compileSeparate(input: AllTransformed)(using Context): Option[Document]

  // Using the methods above, we can implement the required phases.
  val whole = Phase("compile-whole") { input =>
    val mainSymbol = summon[Context].checkMain(input.mod)
    compileWhole(input, mainSymbol)
  }

  val separate = Phase("compile-separate") { all => compileSeparate(all) map { doc => (all.main, doc) } }
}
