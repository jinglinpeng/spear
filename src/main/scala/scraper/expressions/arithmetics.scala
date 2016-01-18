package scraper.expressions

import scala.util.{Failure, Success, Try}

import scraper.Row
import scraper.exceptions.TypeMismatchException
import scraper.expressions.Cast.{promoteDataType, widestTypeOf}
import scraper.plans.physical.NullSafeOrdering
import scraper.types._
import scraper.utils._

trait ArithmeticExpression extends Expression {
  lazy val numeric = dataType match {
    case t: NumericType => t.genericNumeric
  }
}

trait UnaryArithmeticOperator extends UnaryOperator with ArithmeticExpression {
  override lazy val strictlyTypedForm: Try[Expression] = for {
    strictChild <- child.strictlyTypedForm map {
      case NumericType(e)            => e
      case NumericType.Implicitly(e) => promoteDataType(e, NumericType.defaultType)
      case e                         => throw new TypeMismatchException(e, classOf[NumericType])
    }
  } yield if (strictChild sameOrEqual child) this else makeCopy(strictChild :: Nil)

  override lazy val dataType: DataType = whenWellTyped(strictlyTypedForm.get match {
    case e: UnaryArithmeticOperator => e.child.dataType
  })
}

case class Negate(child: Expression) extends UnaryArithmeticOperator {
  override def operator: String = "-"

  override def nullSafeEvaluate(value: Any): Any = numeric.negate(value)
}

case class Positive(child: Expression) extends UnaryArithmeticOperator {
  override def operator: String = "+"

  override def nullSafeEvaluate(value: Any): Any = value
}

trait BinaryArithmeticOperator extends ArithmeticExpression with BinaryOperator {
  override lazy val strictlyTypedForm: Try[Expression] = {
    val checkBranch: Expression => Try[Expression] = {
      case NumericType(e)            => Success(e)
      case NumericType.Implicitly(e) => Success(e)
      case e                         => Failure(new TypeMismatchException(e, classOf[NumericType]))
    }

    for {
      lhs <- left.strictlyTypedForm flatMap checkBranch
      rhs <- right.strictlyTypedForm flatMap checkBranch

      // Figures out the final data type of this arithmetic expression. Basically there are two
      // cases:
      //
      //  - The data type of at least one side is NumericType.  In this case, we use the wider type
      //    of the two as the final data type.
      //
      //  - The data type of neither side is NumericType, but both can be converted to NumericType
      //    implicitly.  In this case, we use the default NumericType as the final data type.
      t <- (lhs.dataType, rhs.dataType) match {
        case (t1: NumericType, t2) => t1 widest t2
        case (t1, t2: NumericType) => t1 widest t2
        case (t1, t2)              => Success(NumericType.defaultType)
      }

      newChildren = promoteDataType(lhs, t) :: promoteDataType(rhs, t) :: Nil
    } yield if (sameChildren(newChildren)) this else makeCopy(newChildren)
  }

  override protected def strictDataType: DataType = left.dataType
}

case class Add(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any = numeric.plus(lhs, rhs)

  override def operator: String = "+"
}

case class Minus(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any = numeric.minus(lhs, rhs)

  override def operator: String = "-"
}

case class Multiply(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any = numeric.times(lhs, rhs)

  override def operator: String = "*"
}

case class Divide(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  private lazy val div = whenStrictlyTyped {
    dataType match {
      case t: FractionalType => t.genericFractional.div _
      case t: IntegralType   => t.genericIntegral.quot _
    }
  }

  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any = if (rhs == 0) null else div(lhs, rhs)

  override def operator: String = "/"
}

case class IsNaN(child: Expression) extends UnaryExpression {
  override lazy val strictlyTypedForm: Try[Expression] = for {
    strictChild <- child.strictlyTypedForm map {
      case FractionalType(e)            => e
      case FractionalType.Implicitly(e) => e
      case e =>
        throw new TypeMismatchException(e, classOf[FractionalType])
    }

    finalType = strictChild.dataType match {
      case t: FractionalType            => t
      case FractionalType.Implicitly(t) => FractionalType.defaultType
    }

    promotedChild = promoteDataType(strictChild, finalType)
  } yield if (promotedChild sameOrEqual child) this else copy(child = promotedChild)

  override def dataType: DataType = BooleanType

  override def evaluate(input: Row): Any = {
    val value = child evaluate input
    if (value == null) false else dataType match {
      case DoubleType => value.asInstanceOf[Double].isNaN
      case FloatType  => value.asInstanceOf[Float].isNaN
      case _          => false
    }
  }
}

abstract class GreatestLeastLike extends Expression {
  assert(children.nonEmpty)

  override protected def strictDataType: DataType = children.head.dataType

  override def strictlyTypedForm: Try[Expression] = for {
    strictChildren <- sequence(children map (_.strictlyTypedForm))
    widestType <- widestTypeOf(strictChildren map (_.dataType)) map {
      case t: OrderedType => t
      case t              => throw new TypeMismatchException(this, classOf[OrderedType])
    }
    promotedChildren = strictChildren.map(promoteDataType(_, widestType))
  } yield if (sameChildren(promotedChildren)) this else makeCopy(promotedChildren)

  protected lazy val ordering = new NullSafeOrdering(strictDataType)
}

case class Greatest(children: Seq[Expression]) extends GreatestLeastLike {
  override def evaluate(input: Row): Any = children map (_ evaluate input) max ordering
}

case class Least(children: Seq[Expression]) extends GreatestLeastLike {
  override def evaluate(input: Row): Any = children map (_ evaluate input) min ordering
}
