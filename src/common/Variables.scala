package scala.virtualization.lms
package common

import java.io.PrintWriter
import scala.virtualization.lms.util.OverloadHack

trait Variables extends Base with OverloadHack {
  type Var[+T]

  implicit def readVar[T:Manifest](v: Var[T]) : Rep[T]
  //implicit def chainReadVar[T,U](x: Var[T])(implicit f: Rep[T] => U): U = f(readVar(x))

  def __newVar[T](init: Rep[T])(implicit o: Overloaded1, mT: Manifest[T]) = var_new(init)
  def __newVar[T](init: Var[T])(implicit o: Overloaded2, mT: Manifest[T]) = var_new(init)

  def __assign[T:Manifest](lhs: Var[T], rhs: T) = var_assign(lhs, rhs)
  def __assign[T](lhs: Var[T], rhs: Rep[T])(implicit o: Overloaded1, mT: Manifest[T]) = var_assign(lhs, rhs)
  def __assign[T](lhs: Var[T], rhs: Var[T])(implicit o: Overloaded2, mT: Manifest[T]) = var_assign(lhs, readVar(rhs))

  // TODO: why doesn't this implicit kick in automatically?
  def infix_+=[T:Manifest](lhs: Var[T], rhs: T) = var_plusequals(lhs, rhs)
  def infix_+=[T](lhs: Var[T], rhs: Rep[T])(implicit o: Overloaded1, mT: Manifest[T]) = var_plusequals(lhs,rhs)
  def infix_+=[T](lhs: Var[T], rhs: Var[T])(implicit o: Overloaded2, mT: Manifest[T]) = var_plusequals(lhs,readVar(rhs))

  def var_new[T:Manifest](init: Rep[T]): Var[T]
  def var_assign[T:Manifest](lhs: Var[T], rhs: Rep[T]): Rep[Unit]
  def var_plusequals[T:Manifest](lhs: Var[T], rhs: Rep[T]): Rep[Unit]
}

trait VariablesExp extends Variables with EffectExp {
  type Var[+T] = Variable[T]
  // TODO: make a design decision here.
  // defining Var[T] as Sym[T] is dangerous. If someone forgets to define a more-specific implicit conversion from
  // Var[T] to Ops, e.g. implicit def varToRepStrOps(s: Var[String]) = new RepStrOpsCls(varToRep(s))
  // then the existing implicit from Rep to Ops will be used, and the ReadVar operation will be lost.
  // Defining Vars as separate from Exps will always cause a compile-time error if the implicit is missing.
  //type Var[T] = Sym[T]

  // REMARK: Var[T] should (probably) be different from Rep[T] in Rep-world
  // but in Exp-world the situation is less clear. Another thing is that in general, 
  // programs should live in Rep-world.
  // Currently DeliteApplication extends DeliteOpsExp and therefore
  // all DSL programs live in Exp-world.

  // read operation
  implicit def readVar[T:Manifest](v: Var[T]) : Exp[T] = { // carefule with implicits...
/*
    //reflectRead(/*v*/)(ReadVar(v)) // FIXME!!
    //reflectEffect(ReadVar(v))
    
    // do cse *in context*
    
    context.reverse.dropWhile { e => 
      e match { case Def(Reflect(ReadVar(w), _)) if w != v => true case _ => false }
    } match {
      case (e @ Def(Reflect(ReadVar(`v`), _)))::es => e.asInstanceOf[Exp[T]]
      case es =>
        val r = createDefinition(fresh[T], Reflect(ReadVar(v), es)).sym
        context = context :+ r
        r
    }*/
    toAtom(ReadVar(v))
  }

  case class ReadVar[T:Manifest](v: Var[T]) extends Def[T]
  case class NewVar[T:Manifest](init: Exp[T]) extends Def[T]
  case class Assign[T:Manifest](lhs: Var[T], rhs: Exp[T]) extends Def[Unit]
  case class VarPlusEquals[T:Manifest](lhs: Var[T], rhs: Exp[T]) extends Def[Unit]


  def var_new[T:Manifest](init: Exp[T]): Var[T] = {
    //reflectEffect(NewVar(init)).asInstanceOf[Var[T]]
    Variable(reflectMutable(NewVar(init)))
  }

  def var_assign[T:Manifest](lhs: Var[T], rhs: Exp[T]): Exp[Unit] = {
    reflectWrite(lhs.e)()(Assign(lhs, rhs))
    Const()
  }

  def var_plusequals[T:Manifest](lhs: Var[T], rhs: Exp[T]): Exp[Unit] = {
    reflectWrite(lhs.e)()(VarPlusEquals(lhs, rhs))
    Const()
  }
  // TODO: not using these due to a problem with getBlockResult() getting an out-of-scope symbol without the Const
  //def var_assign[T:Manifest](lhs: Var[T], rhs: Exp[T]) = reflectMutation(Assign(lhs, rhs))
  //def var_plusequals[T:Numeric:Manifest](lhs: Var[T], rhs: Exp[T]) = reflectMutation(VarPlusEquals(lhs, rhs))
}


trait ScalaGenVariables extends ScalaGenEffect {
  val IR: VariablesExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = rhs match {
    case ReadVar(Variable(a)) => emitValDef(sym, quote(a))
    case NewVar(init) => emitVarDef(sym, quote(getBlockResult(init)))
    case Assign(Variable(a), b) => emitAssignment(quote(a), quote(getBlockResult(b)))
    //case Assign(a, b) => emitAssignment(quote(a), quote(b))
    case VarPlusEquals(Variable(a), b) => emitValDef(sym, quote(a) + " += " + quote(getBlockResult(b)))
    case _ => super.emitNode(sym, rhs)
  }
}

trait CLikeGenVariables extends CLikeGenBase {
  val IR: VariablesExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = {
      rhs match {
        case ReadVar(Variable(a)) =>
          emitValDef(sym, quote(a))
        case NewVar(init) =>
          emitVarDef(sym, quote(getBlockResult(init)))
        case Assign(Variable(a), b) =>
          emitAssignment(quote(a), quote(getBlockResult(b)))
        case VarPlusEquals(Variable(a), b) =>
          emitAssignment(quote(a), quote(a) + " + " + quote(getBlockResult(b)))
        case _ => super.emitNode(sym, rhs)
      }
    }
}

trait CudaGenVariables extends CudaGenEffect with CLikeGenVariables
trait CGenVariables extends CGenEffect with CLikeGenVariables
