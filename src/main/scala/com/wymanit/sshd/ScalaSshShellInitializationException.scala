package com.wymanit.sshd

/**
 * This Exception is thrown when you initialize the ScalaSshShell with wrong or insufficient arguments. E.g. this can
 * be thrown if you do not activate PasswordAuthentication AND PublickeyAuthentication
 *
 * Created by marcusheese on 26/02/14.
 */
object ScalaSshShellInitializationException {
  def apply = new ScalaSshShellInitializationException
  def apply(msg: String) = new ScalaSshShellInitializationException(msg)
  def apply(msg: String, cause: Throwable) = new ScalaSshShellInitializationException(msg, cause)
}
class ScalaSshShellInitializationException(msg:String=null, cause:Throwable=null) extends java.lang.Exception (msg, cause) {}