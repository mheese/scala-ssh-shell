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

Usage
-----

Embed this in your code by running the following:

    val sshd = new ScalaSshShell(port=4444, replName="test", user="user",
                                 passwd="fluke",
                                 keysResourcePath=Some("/test.ssh.keys"),
                                 usejavacp=true)
    sshd.bind("pi", 3.1415926)
    sshd.bind("nums", Vector(1,2,3,4,5))
    sshd.start()

Most of that should be self explanatory. The 'replName' is the name that
will be used for the parent thread, as well as the name that will
appear in the prompt that the user sees. A good idea is to name it
after the service the jvm is providing so if the user sshes into the
wrong jvm they'll immediately see a visual indication that they aren't
where they expected to be. The usejavacp option needs to be set to false
if you are running the code from within SBT!

To shut down ssh service, call:

    sshd.stop()

To generate your keys run ScalaSshShell.generateKeys(), which can be
done from a scala shell:

    scala> com.wymanit.sshd.ScalaSshShell.generateKeys("src/main/resources/test.ssh.keys")

The shell works when running under sbt's console if you instantiate it with usejavacp=false.

To run the included example, run the following with sbt 0.13.1:

    $ sbt update
    $ sbt compile
    $ sbt package
    $ scala -cp "$(find lib_managed | grep jar$ | xargs echo | sed -e 's# #:#g'):./target/scala-2.10.3/scala-ssh-shell_2.10.3-0.0.1.jar" com.wymanit.sshd.ScalaSshShell

Now you can ssh in from a separate window, using "fluke" for the
password:

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
