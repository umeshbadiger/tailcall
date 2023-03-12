package tailcall.runtime

import caliban.InputValue
import tailcall.runtime.ast.Blueprint
import tailcall.runtime.dsl.scala.Orc
import tailcall.runtime.dsl.scala.Orc.Field
import tailcall.runtime.http.HttpClient
import tailcall.runtime.remote._
import tailcall.runtime.service._
import zio.ZIO
import zio.http.Client
import zio.schema.DynamicValue
import zio.test.Assertion.equalTo
import zio.test.{ZIOSpecDefault, assertZIO}

object StepGeneratorSpec extends ZIOSpecDefault {

  def spec = {
    suite("StepGenerator")(
      test("static value") {
        val orc     = Orc("Query" -> List("id" -> Field.output.to("String").resolveWith(100)))
        val program = execute(orc)("query {id}")
        assertZIO(program)(equalTo("""{"id":100}"""))
      },
      test("with args") {
        val orc     = Orc(
          "Query" -> List(
            "sum" -> Field.output.to("Int").withArgument("a" -> Field.input.to("Int"), "b" -> Field.input.to("Int"))
              .resolveWithFunction { ctx =>
                {
                  (for {
                    a <- ctx.toTypedPath[Int]("args", "a")
                    b <- ctx.toTypedPath[Int]("args", "b")
                  } yield a + b).toDynamic
                }
              }
          )
        )
        val program = execute(orc)("query {sum(a: 1, b: 2)}")
        assertZIO(program)(equalTo("""{"sum":3}"""))
      },
      test("with nesting") {
        // type Query {foo: Foo}
        // type Foo {bar: Bar}
        // type Bar {value: Int}

        val orc = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.to("Bar")),
          "Bar"   -> List("value" -> Field.output.to("Int").resolveWith(100))
        )

        val program = execute(orc)("query {foo { bar { value }}}")
        assertZIO(program)(equalTo("{\"foo\":{\"bar\":{\"value\":100}}}"))
      },
      test("with nesting array") {
        // type Query {foo: Foo}
        // type Foo {bar: [Bar]}
        // type Bar {value: Int}

        val orc = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.to("Bar").asList.resolveWith(List(100, 200, 300))),
          "Bar"   -> List("value" -> Field.output.to("Int").resolveWith(100))
        )

        val program = execute(orc)("query {foo { bar { value }}}")
        assertZIO(program)(equalTo("""{"foo":{"bar":[{"value":100},{"value":100},{"value":100}]}}"""))
      },
      test("with nesting array ctx") {
        // type Query {foo: Foo}
        // type Foo {bar: [Bar]}
        // type Bar {value: Int}
        val orc = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.to("Bar").asList.resolveWith(List(100, 200, 300))),
          "Bar"   -> List("value" -> Field.output.to("Int").resolveWithFunction {
            _.toTypedPath[Int]("value").map(_ + Remote(1)).toDynamic
          })
        )

        val program = execute(orc)("query {foo { bar { value }}}")
        assertZIO(program)(equalTo("""{"foo":{"bar":[{"value":101},{"value":201},{"value":301}]}}"""))
      },
      test("with nesting level 3") {
        // type Query {foo: Foo}
        // type Foo {bar: [Bar]}
        // type Bar {baz: [Baz]}
        // type Baz{value: Int}
        val orc = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.to("Bar").asList.resolveWith(List(100, 200, 300))),
          "Bar"   -> List("baz" -> Field.output.to("Baz").asList.resolveWithFunction {
            _.toTypedPath[Int]("value").map(_ + Remote(1)).toDynamic
          }),
          "Baz"   -> List("value" -> Field.output.to("Int").resolveWithFunction {
            _.toTypedPath[Option[Int]]("value").flatten.map(_ + Remote(1)).toDynamic
          })
        )

        val program = execute(orc)("query {foo { bar { baz {value} }}}")
        assertZIO(program)(equalTo(
          """{"foo":{"bar":[{"baz":[{"value":102}]},{"baz":[{"value":202}]},{"baz":[{"value":302}]}]}}"""
        ))
      },
      test("parent") {
        // type Query {foo: Foo}
        // type Foo {bar: Bar}
        // type Bar{baz: Baz}
        // type Baz{value: Int}
        val orc     = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.to("Bar").resolveWith(100)),
          "Bar"   -> List("baz" -> Field.output.to("Baz").resolveWith(200)),
          "Baz"   -> List("value" -> Field.output.to("Int").resolveWithFunction {
            _.path("parent", "value").map(_.toTyped[Int]).flatten.toDynamic
          })
        )
        val program = execute(orc)("query {foo { bar { baz {value} }}}")
        assertZIO(program)(equalTo("""{"foo":{"bar":{"baz":{"value":100}}}}"""))

      },
      test("partial resolver") {
        // type Query {foo: Foo}
        // type Foo {a: Int, b: Int, c: Int}
        val orc     = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo").resolveWith(Map("a" -> 1, "b" -> 2))),
          "Foo"   -> List(
            "a" -> Field.output.to("Int"),
            "b" -> Field.output.to("Int"),
            "c" -> Field.output.to("Int").resolveWith(3)
          )
        )
        val program = execute(orc)("query {foo { a b c }}")
        assertZIO(program)(equalTo("""{"foo":{"a":1,"b":2,"c":3}}"""))

      },
      test("default property resolver") {
        // type Query {foo: Foo}
        // type Foo {a: Int, b: Int, c: Int}
        val orc     = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo").resolveWith(Map("a" -> 1))),
          "Foo"   -> List("a" -> Field.output.to("Int"))
        )
        val program = execute(orc)("query {foo { a }}")
        assertZIO(program)(equalTo("""{"foo":{"a":1}}"""))

      },
      test("mutation with input type") {
        // mutation createFoo(input: FooInput){foo: String}
        // input FooInput {a: Int, b: Int, c: Int}
        val orc     = Blueprint(
          Blueprint.SchemaDefinition(query = Option("Query"), mutation = Option("Mutation"), subscription = None),
          List(
            Blueprint.ObjectTypeDefinition(
              name = "Query",
              fields = List(Blueprint.FieldDefinition(name = "foo", Nil, Blueprint.NamedType("Foo", false)))
            ),
            Blueprint.ObjectTypeDefinition(
              name = "Mutation",
              fields = List(Blueprint.FieldDefinition(
                name = "createFoo",
                List(Blueprint.InputValueDefinition(name = "input", Blueprint.NamedType("FooInput", false), None)),
                Blueprint.NamedType("Foo", false),
                Option(Remote.toLambda((_: Remote[DynamicValue]) => Remote(Map("a" -> 1)).toDynamic))
              ))
            ),
            Blueprint.InputObjectTypeDefinition(
              name = "FooInput",
              fields = List(Blueprint.InputValueDefinition(name = "a", Blueprint.NamedType("Int", false), None))
            )
          )
        )
        val program = execute(orc, Map.empty)("mutation {createFoo(input: {a: 1}){a}}")
        assertZIO(program)(equalTo("""{"createFoo":{"a":1}}"""))
      }
    ).provide(
      GraphQLGenerator.live,
      TypeGenerator.live,
      StepGenerator.live,
      EvaluationRuntime.live,
      HttpClient.live,
      Client.default
    )
  }

  def execute(orc: Orc, variables: Map[String, InputValue] = Map.empty)(
    query: String
  ): ZIO[GraphQLGenerator, Throwable, String] = orc.toBlueprint.flatMap(execute(_, variables)(query))

  def execute(doc: Blueprint, variables: Map[String, InputValue])(
    query: String
  ): ZIO[GraphQLGenerator, Throwable, String] =
    for {
      graphQL     <- doc.toGraphQL
      interpreter <- graphQL.interpreter
      result      <-
        interpreter
          .execute(
            query,
            skipValidation = true,
            variables = variables
          ) // TODO: enable validation after __type is available
      _           <- result.errors.headOption match {
        case Some(error) => ZIO.fail(error)
        case None        => ZIO.unit
      }
    } yield result.data.toString
}