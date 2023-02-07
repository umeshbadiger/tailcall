package tailcall.gateway.remote

trait UnsafeEvaluator  {
  final def evaluateAs[A](eval: DynamicEval): A = evaluate(eval).asInstanceOf[A]
  def evaluate(eval: DynamicEval): Any
}
object UnsafeEvaluator {
  import DynamicEval._
  import scala.collection.mutable

  final class Default(val bindings: mutable.Map[Int, Any]) extends UnsafeEvaluator {
    def evaluate(eval: DynamicEval): Any = eval match {
      case Literal(value, meta)        => value.toTypedValue(meta.toSchema) match {
          case Right(value) => value
          case Left(value)  => throw new RuntimeException("Could not translate literal: " + value)
        }
      case EqualTo(left, right, tag)   => tag.equal(evaluate(left), evaluate(right))
      case Math(operation, tag)        => operation match {
          case Math.Binary(left, right, operation) =>
            val leftValue  = evaluate(left)
            val rightValue = evaluate(right)
            operation match {
              case Math.Binary.Add      => tag.add(leftValue, rightValue)
              case Math.Binary.Multiply => tag.multiply(leftValue, rightValue)
              case Math.Binary.Divide   => tag.divide(leftValue, rightValue)
              case Math.Binary.Modulo   => tag.modulo(leftValue, rightValue)
            }
          case Math.Unary(value, operation)        =>
            val a = evaluate(value)
            operation match { case Math.Unary.Negate => tag.negate(a) }
        }
      case Logical(operation)          => operation match {
          case Logical.Binary(left, right, operation) =>
            val leftValue  = evaluateAs[Boolean](left)
            val rightValue = evaluateAs[Boolean](right)
            operation match {
              case Logical.Binary.And => leftValue && rightValue
              case Logical.Binary.Or  => leftValue || rightValue
            }
          case Logical.Unary(value, operation)        =>
            val a = evaluateAs[Boolean](value)
            operation match {
              case Logical.Unary.Not                      => !a
              case Logical.Unary.Diverge(isTrue, isFalse) =>
                if (a) evaluate(isTrue) else evaluate(isFalse)
            }
        }
      case StringOperations(operation) => operation match {
          case StringOperations.Concat(left, right) =>
            evaluateAs[String](left) ++ evaluateAs[String](right)
        }
      case SeqOperations(operation)    => operation match {
          case SeqOperations.Concat(left, right)    =>
            evaluateAs[Seq[_]](left) ++ evaluateAs[Seq[_]](right)
          case SeqOperations.IndexOf(seq, element)  => evaluateAs[Seq[_]](seq)
              .indexOf(evaluate(element))
          case SeqOperations.Reverse(seq)           => evaluateAs[Seq[_]](seq).reverse
          case SeqOperations.Filter(seq, condition) => evaluateAs[Seq[_]](seq)
              .filter(call[Boolean](condition, _))

          case SeqOperations.FlatMap(seq, operation) => evaluateAs[Seq[_]](seq)
              .flatMap(call[Seq[_]](operation, _))
          case SeqOperations.Length(seq)             => evaluateAs[Seq[_]](seq).length
          case SeqOperations.Sequence(value)         => value.map(evaluate(_))
        }
      case EitherOperations(operation) => operation match {
          case EitherOperations.Cons(value)              => value match {
              case Left(value)  => Left(evaluate(value))
              case Right(value) => Right(evaluate(value))
            }
          case EitherOperations.Fold(value, left, right) => evaluate(value) match {
              case Left(value)  => call(left, value)
              case Right(value) => call(right, value)
            }
        }
      case FunctionCall(f, arg)        => call(f, evaluate(arg))
      case Binding(id)                 => bindings
          .getOrElse(id, throw new RuntimeException("Could not find binding: " + id))
      case EvalFunction(_, body)       => evaluate(body)
    }

    def call[A](func: EvalFunction, arg: Any): A = {
      bindings.addOne(func.input.id -> arg)
      val result = evaluateAs[A](func.body)
      bindings.drop(func.input.id)
      result
    }
  }

  def make(bindings: mutable.Map[Int, Any] = mutable.Map.empty): UnsafeEvaluator =
    new Default(bindings)
}