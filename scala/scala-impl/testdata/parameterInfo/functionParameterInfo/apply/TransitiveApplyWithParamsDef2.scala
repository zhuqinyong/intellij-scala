trait TraitWithApply {
  def apply(i: Int, s: String): Unit = {}
}

object TransitiveApplyWithParamsDef2 {
  def foo(b: Boolean): TraitWithApply = new TraitWithApply {}
}

object Test {
  TransitiveApplyWithParamsDef2.foo(true)(<caret>)
}
//i: Int, s: String