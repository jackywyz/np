package np

import sbt._
import Project.Initialize

case class Defaults(org: String, name: String, version: String,
                    plugin: Boolean = false, dir: String = ".")

private object BuildSbt {

  private val SbtVersion = "{sbtVersion}"

  /** Handles cross sbt versioning.  */
  private def versionBind(version: String, plugin: Boolean) = {
    if(!plugin) """:= "%s"""".format(version)
    else {
      if(version.contains(SbtVersion)) {
        """<<= sbtVersion("%s" format _)""".format(version.replace(SbtVersion, "%s"))
      } else """:= "%s"""".format(version)
    }
  }

  def apply(plugin: Boolean, org: String, name: String, version: String,scalaVersion:String) =
    """%sorganization := "%s"
    |
    |name := "%s"
    |
    |version %s
    |
    |scalaVersion :="%s"""".stripMargin.format(
      if(plugin) "sbtPlugin := true\n\n" else "",
      org,
      name,
      versionBind(version, plugin),
      scalaVersion
    ).trim()
}

private object Usage {
  val display = """
  |Usage: <key>:<value> <key2>:<value2> ...
  |
  |all key arguments have default values
  |
  |Keys
  |  org     Project organization. Defaults to sbt built-in default
  |  name    Project name. Defaults to sbt built-in default
  |  version Project version. Defaults to sbt built-in default
  |  plugin  Boolean indicator of whether the project is a plugin project. Defaults to false
  |  dir     Path to dir where np should generate project. Defaults to '.'
  |""".stripMargin
}

object Plugin extends sbt.Plugin {
  import sbt.Keys._
  import NpKeys.{ np => npkey, _ => _ }
  import java.io.File
  import java.lang.Boolean.{ parseBoolean => bool }

  object NpKeys {
    val np = InputKey[Unit]("np", "Sbt project generator")
    val defaults = SettingKey[Defaults]("defaults", "Default options used to generate projects")
    val scout = InputKey[Unit]("scout", "Does a dry run to check for conflicts")
    val usage = TaskKey[Unit]("usage", "Displays np usage info")
  }
  var LANG_TYPE = "scala"
  private def extract(args: Seq[String], pbase: File, defaults: Defaults) = {

    val Name  = """name\:(\S+)""".r
    val Vers  = """version\:(\S+)""".r
    val Org   = """org\:(\S+)""".r
    val Plgin = """plugin\:(\S+)""".r
    val Dir   = """dir\:(\S+)""".r
    val SVersion= """sversion\:(\S+)""".r
    val Type= """type\:(\S+)""".r

    def first[T](default: T)(pf: PartialFunction[String, T]) =
      args.collect(pf).headOption.getOrElse(default)

    val (p, o, n, v, l,d) = (
      first(false) { case Plgin(p) => bool(p) },
      first(defaults.org)   { case Org(o)   => o },
      first(defaults.name)  { case Name(n)  => n },
      first(defaults.version) { case Vers(v)  => v },
      first(defaults.scalaVersion) { case SVersion(l)  => l },
      first(defaults.dir)   { case Dir(d) => d }
    )
    
    LANG_TYPE = first(LANG_TYPE) {case Type(t) => t} 
    
    (BuildSbt(p, o, n, v,l), d match {
      case "." => pbase
      case path => new File(pbase, path)
    })
  }

  private def configDirs(conf: String)(base: File) =
    Seq(LANG_TYPE, "resources") map { d =>
      new File(base, "src/%s/%s".format(conf,d))
    }

  private def mainDirs = configDirs("main")_

  private def testDirs = configDirs("test")_

  private def genDirs(base: File): Seq[File] =
    mainDirs(base) ++ testDirs(base)

  private def usageTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        out.log.info(Usage.display)
    }

  def npSettings0: Seq[Setting[_]] = Seq(
    defaults in npkey <<= /*(defaults in npkey) or */(
      name, organization, version)(Defaults(_, _, _)),
    usage in npkey <<= usageTask,
    scout in npkey <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, baseDirectory, streams, defaults in npkey) map {
        (args, bd, out, defaults) =>
          val (_, base) = extract(args, bd, defaults)
          val (bf, dirs) = (
            new File(base, "build.sbt"),
            genDirs(base)
          )
          dirs :+ bf filter(_.exists) match {
            case Nil =>
              out.log.info("Looks good. Run `np` task to generate project.")
            case existing =>
              out.log.warn("The following files/directories already exist\n\n%s".format(
                existing.mkString("\n\n"))
              )
          }
      }
    },
    npkey <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, baseDirectory, streams, defaults in npkey) map {
        (args, bd, out, defaults) =>
          val (scpt, base) = extract(args, bd, defaults)
          val (bf, dirs) = (new File(base, "build.sbt"), genDirs(base))

          // error out if any of the target files to generate
          // already exist
          if((dirs :+ bf).find(_.exists).isDefined) sys.error(
            "\nexisting project detected at the path %s" format bf.getParent
          )

          IO.write(bf, scpt)
          out.log.info("Generated build file")

          IO.createDirectories(dirs)
          out.log.info("Generated source directories")
       }
    }
  )

  def npSettingsIn(c: Configuration) = inConfig(c)(npSettings0)

  // will auto-mix into project settings
  def npSettings: Seq[Setting[_]] =
    npSettingsIn(Test) ++ npSettingsIn(Compile)
}
