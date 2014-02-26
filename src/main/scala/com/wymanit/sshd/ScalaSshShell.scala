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
import java.io.{InputStreamReader, FileInputStream, PrintWriter}
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.common.keyprovider.AbstractKeyPairProvider
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.{PublickeyAuthenticator, PasswordAuthenticator, Command}
import org.apache.sshd.common.{KeyPairProvider, Factory}
import scala.reflect._
import scala.tools.nsc.interpreter._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.tools.nsc.Settings
import java.security.PublicKey
import scala.io.Source
import java.lang.Iterable

class ScalaSshShell(
  val port: Int,
  val replName: String,
  val user: String,
  val passwd: Option[String],
  val hostKeyPath: Option[List[String]] = None,
  val host: Option[List[String]] = None,
  val authorizedKeysPath: Option[String] = None,
  val showScalaWelcomeMessage: Boolean = true,
  val additionalWelcomeMessage: Option[String] = None,
  val initialBindings: Option[List[(String, String, Any)]] = None,
  val initialCmds: Option[List[String]] = None,
  val usejavacp: Boolean = true
) extends BouncyCastleProvider with Shell with Logging {

  def authorizedKeysResourcePath = "/authorized_keys"
  def hostKeyResourcePath = List("/ssh_host_dsa_key", "/ssh_host_ecdsa_key", "/ssh_host_rsa_key")

  val pwAuth: Option[PasswordAuthenticator] = passwd.map { password =>
    Some(new PasswordAuthenticator {
      def authenticate(u: String, p: String, s: ServerSession) =
        u == user && p == password
    })
  }.getOrElse {
    logger.info("PasswordAuthenticator: disabling password authentication as no password has been provided!")
    None
  }

  val pkAuth: Option[PublickeyAuthenticator] = {

    val akd = new AuthorizedKeysDecoder
    def retrievePubKeys(is: java.io.InputStream): List[PublicKey] = Source.fromInputStream(is, "UTF-8").getLines().flatMap { line =>
      try {
        Some(akd.decodePublicKey(line))
      } catch {
        case e: Throwable =>
          logger.warn(s"PublickeyAuthenticator: Skipping PublicKey because there was an error reading it: ${e.getMessage}")
          None
      }
    }.toList

    // All keys read from classpath resources
    val resourceKeys: Option[List[PublicKey]] = {
      val tmp = classOf[ScalaSshShell].getResourceAsStream(authorizedKeysResourcePath)
      if(tmp == null) None
      else Some(retrievePubKeys(tmp))
    }

    // All keys read from provided path
    val providedKeys: Option[List[PublicKey]] = authorizedKeysPath.map { path =>
      try {
        Some(retrievePubKeys(new FileInputStream(path)))
      } catch {
        case e: Throwable =>
          logger.warn(s"PublickeyAuthenticator: Skipping PublicKey from $path because there was an error reading it: ${e.getMessage}")
          None
      }
    }.getOrElse(None)

    // Merge the list
    val pubKeys = List(resourceKeys, providedKeys).flatten.flatten
    if(pubKeys.isEmpty) {
      logger.info("PublickeyAuthenticator: no public keys were loaded. Disabling Publickey authentication.")
      None
    } else {
      logger.info(s"PublickeyAuthenticator: has a total of ${pubKeys.length} keys loaded (${providedKeys.map(_.length.toString).getOrElse("None")} from provided path / ${resourceKeys.map(_.length.toString).getOrElse("None")} from classpath resources)")
      Some(new PublickeyAuthenticator {
        override def authenticate(username: String, key: PublicKey, session: ServerSession): Boolean = {
          user == username && pubKeys.contains(key)
        }
      })
    }
  }

  if(pkAuth.isEmpty && pwAuth.isEmpty) throw ScalaSshShellInitializationException("At least one of PasswordAuthentication and PublickeyAuthentication needs to be enabled!")
}

trait Shell extends BouncyCastleProvider with Logging {
  def port: Int
  def replName: String
  def hostKeyPath: Option[List[String]]
  def hostKeyResourcePath: List[String]
  def authorizedKeysPath: Option[String]
  def authorizedKeysResourcePath: String
  def pwAuth: Option[PasswordAuthenticator]
  def pkAuth: Option[PublickeyAuthenticator]
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
    pwAuth.foreach(pwA => x.setPasswordAuthenticator(pwA))
    pkAuth.foreach(pkA => x.setPublickeyAuthenticator(pkA))
    x.setKeyPairProvider(keyPairProvider)
    x.setShellFactory(new ShellFactory)
    x
  }

  val keyPairProvider: KeyPairProvider = {
    import scala.collection.JavaConversions._
    import java.security.KeyPair
    import org.bouncycastle.openssl._
    import org.bouncycastle.openssl.jcajce._

    def defaultKeyPairProvider = new SimpleGeneratorHostKeyProvider()
    val converter = new JcaPEMKeyConverter()
    def readKeyPair(is: java.io.InputStream): Option[KeyPair] = {
      try {
        val pemParser = new PEMParser(new InputStreamReader(is))
        val pemKeyPair = pemParser.readObject().asInstanceOf[PEMKeyPair]
        Some(converter.getKeyPair(pemKeyPair))
      } catch {
        case e: Throwable =>
          logger.warn(s"HostKeyPairProvider: Skipping Host KeyPair because there was an error while trying to read it: ${e.getMessage}")
          None
      }
    }

    // All host keys loaded from classpath resources
    val resourceKeys: List[KeyPair] = hostKeyResourcePath.map { path =>
      val tmp = classOf[ScalaSshShell].getResourceAsStream(path)
      if(tmp == null) None
      else {
        readKeyPair(tmp)
      }
    }.flatten

    // All host keys loaded from provided resources
    val providedKeys: List[KeyPair] = hostKeyPath.map(_.map { path =>
      try {
        readKeyPair(new FileInputStream(path))
      } catch {
        case e: Throwable =>
          logger.warn(s"HostKeyPairProvider: Skipping Host KeyPair from $path because there was an error reading it: ${e.getMessage}")
          None
      }
    }).getOrElse(Nil).flatten

    // Merge the list
    val keys: List[KeyPair] = resourceKeys ::: providedKeys
    if(keys.isEmpty) {
      logger.info("HostKeyPairProvider: No Host KeyPairs were loaded. Falling back to SimpleGeneratorHostKeyProvider to generate keys for you.")
      defaultKeyPairProvider
    }
    else {
      logger.info(s"HostKeyPairProvider: has a total of ${keys.length} host KeyPairs loaded (${providedKeys.length} from provided paths / ${resourceKeys.length} from classpath resources)")
      new AbstractKeyPairProvider {
        override def loadKeys(): Iterable[KeyPair] = keys
      }
    }
  }

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
                                 passwd=Some("fluke"))
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