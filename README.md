scala-ssh-shell
===============

__Note:__ This is a fork from [peak6/scala-ssh-shell](https://github.com/peak6/scala-ssh-shell). It was always awesome to have this scala SSH shell, and one can do amazing things with it. However, peak6 stopped developing on it, and also the other forks were not too promising, so I decided to go on with it. Now it works already with scala 2.10 and also from within a running sbt. New features are also planned.

About
-----

Backdoor that gives you a scala shell over ssh on your jvm

NOTE: The shell is not sandboxed, anyone access the shell can touch
anything in the jvm and do anything the jvm can do including modifying
and deleting files, etc. Use at your own risk. No guarantees are made
regarding this being secure.


Version
-------

I now released the 0.0.2 of scala-ssh-shell (26th Feb 2014).

It brings the following new features:
- SSH Host Keys are now loaded from standard PEM encoded files (OpenSSL standard, and the default for OpenSSH host keys)
- PublickeyAuthentication now possible: provide an *authorized_keys* file on the classpath or via the constructor
- scala repl welcome message is switchable + you can provide another own one
- initial commands via constructor: you can add a list of commands that should always be evaluated when a user connects
  (very convenient for _import_ statements)
- initial bindings via constructor: you can add a list of bindings that should be set when a user connects
- specific IP address bindings: you can provide a list of interfaces that the sshd should bind to


Installation
------------

Unfortunately there is no maven/ivy repository for it yet, so
you have to live with a local published version (in sbt console, enter `publishLocal` to achieve this) for now.


TODOs
-----

- there is a lot of mutable stuff which probably can be removed from the `ScalaSshShell` (initial commands and bindings
  mainly)
- more convenient constructor with an `apply` method on ScalaSshShell object (trivial)
- the `CrashingThread` can be reworked
- better _exit_ strategy from the "shell". At the moment _exit_ is a function which is always just bound to every repl,
  however, in scala starting from 2.10, _exit_ is deprecated and is now in `sys.exit`. Calling that freezes the repl.
  There should be a possibility that one can type both and we can probably intercept them.

A midterm plan could be to make a better standalone application out of it. It is pretty simple to turn this into an
Akka application providing the ScalaSshShell as an actor. This could probably also help with the CrashingThread and
it can maybe replaced with a good Akka supervisor strategy. But maybe this should be handled in a separate project,
which keeps Akka out of the dependencies then.


Usage
-----

You can put the following resource files on your classpath:

- /authorized_keys : an OpenSSH-style "authorized_keys" file
- /ssh_host_dsa_key : an OpenSSH-style DSA host key
- /ssh_host_ecdsa_key : an OpenSSH-style ECDSA host key
- /ssh_host_rsa_key : an OpenSSH-style RSA key

None of the specified keys replace each other. All keys are merged to a list. If you also provide additional files with
the constructor (see below), then the keys will be added to an even longer list. Like this, you can easily e.g.
provide some fallback/backup keys on the classpath while you can also specify user keys on the command line.

After setting up your resources, embed the following in your code by running:

    val sshd = new ScalaSshShell(
      port = 4444,
      replName = "scala-shell",
      user = "user",
      passwd = Some("fluke"),                                        // If you set passwd to None,
                                                                     // PasswordAuthentication gets disabled

      // All the following parameters are OPTIONAL and have default values!

      hostKeyPath = Some(List(                                       // A list of OpenSSH-style host key file paths
        "/tmp/ssh_host_rsa_key",
        "/tmp/ssh_host_dsa_key"
      )),
      host = Some(List(                                              // A list of all interfaces that sshd should bind
        "127.0.0.1",                                                 // bind to.
        "192.168.0.123"
      )),
      authorizedKeysPath = Some("/tmp/authorized_keys"),             // Path to an OpenSSH-style "authorized_keys" file
      showScalaWelcomeMessage = false,                               // Disables the Scala REPL welcome message
      additionalWelcomeMessage = Some(                               // An additional welcome message that will be
        "Hello, welcome to your own scala shell on Apache MINA!"     // printed on login
      ),
      initialBindings = Some(List(                                   // initial bindings (same as if you add it later
        ("pi", "Double", 3.1415926),                                 // with .bind method)
        ("nums", "Vector[Int]", Vector(1,2,3,4,5))
      )),
      initialCmds = Some(List(                                       // initial commands (same as if you add it later
        "import java.util.Date"                                      // with .addInitCommand method)
      )),
      usejavacp = false                                              // Set to false from within SBT console!
    )

    sshd.bind("pi", 3.1415926)
    sshd.bind("nums", Vector(1,2,3,4,5))

    sshd.addInitCommand("import java.util.Date")

    sshd.start()

Most of that should be self explanatory. The 'replName' is the name that
will be used for the parent thread, as well as the name that will
appear in the prompt that the user sees. A good idea is to name it
after the service the jvm is providing so if the user sshes into the
wrong jvm they'll immediately see a visual indication that they aren't
where they expected to be. The usejavacp option needs to be set to false
if you are running the code from within SBT!

__Note:__ You have to have at least either PasswordAuthentication or PublickeyAuthentication activated, or you will
          get an ScalaSshShellInitializationException!

To shut down ssh service, call:

    sshd.stop()

To generate your keys, the easiest way is to run some openssh commands and copy the files to your classpath resources.
If you don't know what that means, `man ssh-keygen` is your friend.

**The shell works when running under sbt's console if you instantiate it with usejavacp=false.**

To run the included example, run the following with sbt 0.13.1:

    $ sbt update
    $ sbt compile
    $ sbt package
    $ scala -cp "$(find lib_managed | grep jar$ | xargs echo | sed -e 's# #:#g'):./target/scala-2.10.3/scala-ssh-shell_2.10.3-0.0.1.jar" com.wymanit.sshd.ScalaSshShell

Now you can ssh in from a separate window, using "fluke" for the
password (or you get automatically authenticated with one of the provided public keys):

    ssh -p 4444 user@127.0.0.1
    Password authentication
    Password:
    Connected to test, starting repl...
    Welcome to Scala version 2.10.3 (Java HotSpot(TM) 64-Bit Server VM, Java 1.7.0_51).
    Type in expressions to have them evaluated.
    Type :help for more information.
    test> pi
    res0: Double = 3.1415926
    test> nums.sum
    res0: Int = 15
    test> pi/2
    res1: Double = 1.5707963
    test> println("Hello World!")
    Hello World!
