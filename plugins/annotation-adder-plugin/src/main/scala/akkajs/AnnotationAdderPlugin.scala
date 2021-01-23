package akkajs

import scala.tools.nsc.{ Global, Phase }
import scala.tools.nsc.plugins.{ Plugin, PluginComponent }
import scala.tools.nsc.transform.{ Transform, TypingTransformers }
import scala.tools.nsc.symtab.Flags
import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.ast.TreeDSL

import java.nio.file.Files.readAllBytes
import java.nio.file.Paths.get

import scala.collection.mutable
import scala.util.{ Try => STry, Success, Failure }
import scala.reflect.internal.MissingRequirementError

class AnnotationAdderPlugin(val global: Global) extends Plugin {
  import global._

  val name = "annotation-adder-plugin"
  val description = "Want to add annotation to objects, classes, fields, and methods"
  val components = List[PluginComponent](AnnotationAdderComponent)

  lazy val config: mutable.Set[(String, String, List[String])] = {
    val ret = mutable.Set[(String, String, List[String])]()

    ret ++= (try new String(readAllBytes(get("./config/annotation_adder.config"))).split("\n").toSeq.map(e => {
      val splitted = e.split(" ")
      (splitted(0), splitted(1), splitted.drop(2).toList)
    })
    catch {
      case err: Throwable =>
        println("Annotation adder configuration file is missing")
        Seq()
    })

    ret
  }

  private object AnnotationAdderComponent extends PluginComponent with Transform {
    val global = AnnotationAdderPlugin.this.global
    import global._
    import global.definitions._

    override val runsAfter = List("typer")

    val phaseName = "annotation-adder"

    def newTransformer(unit: CompilationUnit) =
      new Transformer {

        val annotators =
          config.flatMap { c =>
            val originalSym = STry {
              try {
                rootMirror.getClassByName((TypeName(c._1)))
              } catch {
                case _: MissingRequirementError => //is not a class get member
                  val i = c._1.lastIndexOf('.')
                  val className = c._1.substring(0, i)
                  val memberName = c._1.substring(i + 1)

                  if (memberName.endsWith("$")) {
                    val d = c._1.lastIndexOf('$')
                    val module = c._1.substring(0, d)

                    rootMirror.staticModule(module)
                  } else {
                    val cl = rootMirror.getClassByName((TypeName(className)))
                    try {
                       getMember(cl, (TermName(memberName)))
                    } catch {
                      case err: Throwable =>
                        err.printStackTrace
                        throw err
                    }
                  }
                case err: Throwable =>
                  err.printStackTrace
                  throw err
              }
            }
            val annotation = STry {
                rootMirror.getClassByName(TypeName(c._2))
            }

            annotation match {
              case Failure(_) =>
                global.reporter.error(rootMirror.universe.NoPosition,
                  s"Malformed annotation class ${c._2}")
                throw new Exception("Malformed configuration file")
              case _ =>
            }

            val params =
                c._3.map(x => { //better could be done ...
                  x match {
                    case "true" => reify(true).tree
                    case "false" => reify(false).tree
                    case str => Literal(Constant(str.toString))
                  }
                })

            (originalSym, annotation) match {
              case (Success(orSym), Success(annotationSym)) =>
                Some(orSym, annotationSym, params)
              case _ =>
                None
            }
          }

        //probably we could avoid to use a transformer and use a traver only
        override def transform(tree: Tree): Tree = {
            tree match {
              case dt : DefTree =>
                val toAnnotate = annotators.filter { case (symb,_,_) =>
                  symb == dt.symbol && symb.owner == dt.symbol.owner}
              if (toAnnotate.size > 0) {
                toAnnotate.foreach { anno =>
                  dt.symbol.addAnnotation(anno._2, anno._3: _*)
                }
                global.reporter.warning(tree.pos, s"Annotation/s ${toAnnotate.map(_._2.nameString).mkString("and ")} added to ${dt.symbol}.")
              }
              case _ =>
            }

          super.transform(tree)
        }
      }
  }
}
