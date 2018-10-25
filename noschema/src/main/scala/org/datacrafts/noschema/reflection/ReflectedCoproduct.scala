package org.datacrafts.noschema.reflection

import scala.reflect.runtime.{universe => ru}

import org.datacrafts.noschema.{Context, NoSchema, NoSchemaCoproduct, NoSchemaProduct, Operation}
import org.datacrafts.noschema.operator.CoproductOperator

class ReflectedCoproduct(
  runtimeType: ru.Type,
  members: Map[ru.Symbol, Context.CoproductElement[Any]]
) extends NoSchemaCoproduct[Any](members.values.toSeq) {

  import org.datacrafts.noschema.NoSchema._

  override lazy val scalaType: NoSchema.ScalaType[Any] =
    new NoSchema.ScalaType[Any](runtimeType.uniqueKey) {
      override lazy val tpe = runtimeType

      override def toString: String = s"RuntimeType[${uniqueKey}]"

      override def matchInput(input: Any): Option[Any] = Option.empty
    }

  lazy val reflector = new TypeReflector(runtimeType)

  override def marshal(typeExtractor: CoproductOperator.TypeValueExtractor,
    operation: Operation[Any]
  ): Any = {
    logDebug(s"marshalling ${typeExtractor} to ${reflector.fullName}")
    (for (
      (memberSymbol, memberContext) <- members;
      matchedValue <- typeExtractor.getTypeValue(memberContext)
    ) yield {
      logDebug(
        s"${reflector.fullName} found matched subclass ${memberContext} for value ${matchedValue}")
      operation.dependencyOperation(memberContext).marshal(matchedValue)
    }).headOption.getOrElse(
      throw new Exception(s"no value among candidate types (${members}) " +
        s"found from $typeExtractor\n" +
        s"${operation.format()}")
    )

  }

  override def unmarshal(input: Any,
    emptyUnion: CoproductOperator.UnionTypeValueCollector,
    operation: Operation[Any]
  ): CoproductOperator.UnionTypeValueCollector = {
    // based on type reflection or result of unmarshaling
    // primitive types just fo type casting must check the type

    val (matchedMember, result) =
    (for (
      (memberSymbol, memberContext) <- members
      if memberSymbol.fullName == input.getClass.getCanonicalName
    ) yield {
      (
        memberContext,
        operation.dependencyOperation(memberContext).unmarshal(input)
      )
    }).headOption.getOrElse(
      throw new Exception(
        s"input=${input.getClass.getCanonicalName} " +
          s"does not match any member ${members.keys.map(_.fullName)}")
    )

    emptyUnion.addTypeValue(matchedMember, result)
  }

}