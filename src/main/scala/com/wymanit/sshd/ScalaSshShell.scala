/*
 * Copyright 2011 PEAK6 Investments, L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wymanit.sshd

import grizzled.slf4j.Logging
import java.io.PrintWriter
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.common.keyprovider.AbstractKeyPairProvider
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.{PublickeyAuthenticator, PasswordAuthenticator, Command}
import org.apache.sshd.common.util.KeyUtils.getKeyType
import org.apache.sshd.common.Factory
import scala.reflect._
import scala.tools.nsc.interpreter._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.tools.nsc.Settings
import java.security.PublicKey
import scala.io.Source
class ScalaSshShell(
  val port: Int,
  val replName: String,
  val user: String,
  val passwd: String,
  val hostKeyResourcePath: Option[String],
  val host: Option[List[String]] = None,
  val authorizedKeyResourcePath: Option[String] = None,
  val showScalaWelcomeMessage: Boolean = true,
  val additionalWelcomeMessage: Option[String] = None,
  val initialBindings: Option[List[(String, String, Any)]] = None,
  val initialCmds: Option[List[String]] = None,
  val usejavacp: Boolean = true
) extends Shell with Logging {
  val pwAuth =
    new PasswordAuthenticator {
      def authenticate(u: String, p: String, s: ServerSession) =
        u == user && p == passwd
    }
  def noPkAuthenticator = new PublickeyAuthenticator {
    override def authenticate(username: String, key: PublicKey, session: ServerSession): Boolean = false
  }
  val pkAuth: PublickeyAuthenticator = authorizedKeyResourcePath.map( resPath => {
    val in = classOf[ScalaSshShell].getResourceAsStream(resPath)
    if(in == null) noPkAuthenticator
    else {
      val akd = new AuthorizedKeysDecoder
      val pubKeys: List[PublicKey] = Source.fromInputStream(in, "UTF-8").getLines().flatMap { line =>
        try {
          Some(akd.decodePublicKey(line))
        } catch {
          case _: Throwable => None
        }
      }.toList
      logger.info(s"PublickeyAuthenticator has ${pubKeys.length} keys loaded")
      new PublickeyAuthenticator {
        override def authenticate(username: String, key: PublicKey, session: ServerSession): Boolean = {
          user == username && pubKeys.contains(key)
        }
      }
    }
  }).getOrElse(
    noPkAuthenticator
  )
}

trait Shell {
  def port: Int
  def replName: String
  def hostKeyResourcePath: Option[String]
  def authorizedKeyResourcePath: Option[String]
  def pwAuth: PasswordAuthenticator
  def pkAuth: PublickeyAuthenticator
  def usejavacp: Boolean
  def host: Option[Seq[String]]
  def showScalaWelcomeMessage: Boolean
  def additionalWelcomeMessage: Option[String]
  def initialBindings: Option[Seq[(String, String, Any)]]
  def initialCmds: Option[Seq[String]]

  var bindings: Seq[(String, String, Any)] = IndexedSeq()
  var initCmds: Seq[String] = IndexedSeq()

  def bind[T: Manifest](name: String, value: T) {
    bindings :+= ((name, manifest[T].toString(), value))
  }

  def bind[T](name: String, boundType: String, value: T) {
    bindings :+= ((name, boundType, value))
  }

  def addInitCommand(cmd: String) {
    initCmds :+= cmd
  }

  lazy val sshd = {
    val x = org.apache.sshd.SshServer.setUpDefaultServer()
    x.setPort(port)
    host.foreach(hostList => x.setHost(hostList.mkString(",")))
    // mheese: setReuseAddress vanished from mina ... still no clue why and if we need it
    //x.setReuseAddress(true)
    x.setPasswordAuthenticator(pwAuth)
    x.setPublickeyAuthenticator(pkAuth)
    x.setKeyPairProvider(keyPairProvider)
    x.setShellFactory(new ShellFactory)
    x
  }
  import scala.collection.JavaConversions._
  def defaultKeyPairProvider = new SimpleGeneratorHostKeyProvider()
  lazy val keyPairProvider =
    hostKeyResourcePath.map {
      krp =>
        // 'private' is one of the most annoying things ever invented.
        // Apache's sshd will only generate a key, or read it from an
        // absolute path (via a string, eg can't work directly on
        // resources), but they do privide protected methods for reading
        // from a stream, but not into the internal copy that gets
        // returned when you call loadKey(), which is of course privite
        // so there is no way to copy it. So we construct one provider
        // so we can parse the resource, and then impliment our own
        // instance of another so we can return it from loadKey(). What
        // a complete waste of time.
        val in = classOf[ScalaSshShell].getResourceAsStream(krp)
        if(in == null) defaultKeyPairProvider
        else {
          new AbstractKeyPairProvider {
            val pair = new SimpleGeneratorHostKeyProvider() {
              val in = classOf[ScalaSshShell].getResourceAsStream(krp)
              val get = doReadKeyPair(in)
            }.get

            override def getKeyTypes = getKeyType(pair)
            override def loadKey(s:String) = pair
            def loadKeys() = Seq[java.security.KeyPair]()
          }
        }
    }.getOrElse(defaultKeyPairProvider)

  class ShellFactory extends Factory[Command] {
    def create() =
      new org.apache.sshd.server.Command with Logging {
        logger.info("Instantiated")
        var in: java.io.InputStream = null
        var out: java.io.OutputStream = null
        var err: java.io.OutputStream = null
        var exit: org.apache.sshd.server.ExitCallback = null
        var thread: Thread = null
        @volatile var inShutdown = false

        def setInputStream(in: java.io.InputStream) { this.in = in}

        def setOutputStream(out: java.io.OutputStream) {
          this.out = new java.io.OutputStream {
            override def close() { out.close() }
            override def flush() { out.flush() }

            override def write(b: Int) {
              if (b.toChar == '\n')
                out.write('\r')
              out.write(b)
            }

            override def write(b: Array[Byte]) {
              var i = 0
              while (i < b.size) {
                write(b(i))
                i += 1
              }
            }

            override def write(b: Array[Byte], off: Int, len: Int) {
              write(b.slice(off, off + len))
            }
          }
        }

        def setErrorStream(err: java.io.OutputStream) { this.err = err }

        def setExitCallback(exit: org.apache.sshd.server.ExitCallback) {
          this.exit = exit
        }

        def start(env: org.apache.sshd.server.Environment) {
          thread = CrashingThread.start(Some("ScalaSshShell-" + replName)) {
            val pw = new PrintWriter(out)
            logger.info("New ssh client connected")
            pw.write("Connected to %s, starting repl...\n".format(replName))
            pw.flush()

            val il = new SshILoop(None, pw)
            il.setPrompt(replName + "> ")
            il.settings = new Settings()
            il.settings.embeddedDefaults(getClass.getClassLoader)
            // usejavacp must be false when started from within SBT
            if(usejavacp) il.settings.usejavacp.value = true
            // some people say that the repl hangs when replsync is not set
            il.settings.Yreplsync.value = true
            // Those three are my standard since scala 2.10
            il.settings.feature.value = true
            il.settings.deprecation.value = true
            il.settings.unchecked.value = true
            // create the interpreter now
            il.createInterpreter()

            // from this point, il.intp is available
            val completor = new JLineCompletion(il.intp)
            il.in = new JLineIOReader(in, out, completor)

            if (il.intp.reporter.hasErrors) {
              logger.error("Got errors, abandoning connection")
              return
            }

            if(showScalaWelcomeMessage) il.printWelcome()
            additionalWelcomeMessage foreach { welcomeMsg =>
              pw.write(s"\n$welcomeMsg\n\n")
              pw.flush()
            }
            try {
              il.intp.initialize(())
              il.intp.beQuietDuring {
                il.intp.bind("stdout", pw)
                for ((bname, btype, bval) <- bindings)
                  il.bind(bname, btype, bval)
              }
              il.intp.quietRun(
                """def println(a: Any) = {
                  stdout.write(a.toString)
                stdout.write('\n')
                }""")
              il.intp.quietRun(
                """def exit = println("Use ctrl-D to exit shell.")""")
              initCmds.foreach(il.intp.quietRun)

              il.loop()
            } finally il.closeInterpreter()

            logger.info("Exited repl, closing ssh.")
            pw.write("Bye.\r\n")
            pw.flush()
            exit.onExit(0)
          }
        }
        def destroy() {
          inShutdown = true
        }
      }
  }

  def start() {
    initialBindings foreach { _ foreach { case (name, boundType, value) =>
      this.bind(name, boundType, value)
    }}
    initialCmds foreach { _ foreach { cmd =>
      this.addInitCommand(cmd)
    }}
    sshd.start()
  }

  def stop() {
    sshd.stop()
  }
}

object ScalaSshShell {
  def main(args: Array[String]) {
    val sshd = new ScalaSshShell(port=4444, replName="test", user="user",
                                 passwd="fluke",
                                 hostKeyResourcePath=Some("/test.ssh.keys"))
    sshd.bind("pi", 3.1415926)
    sshd.bind("nums", Vector(1,2,3,4,5))
    future {
      sshd.start()
    }
    //new java.util.Scanner(System.in) nextLine()
    Thread.sleep(60000)
    sshd.stop()
  }

  def generateKeys(path: String) {
    val key = new SimpleGeneratorHostKeyProvider(path)
    key.loadKeys()
  }
}
