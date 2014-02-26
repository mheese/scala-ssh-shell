package com.wymanit.sshd

import grizzled.slf4j.Logging
import java.security.Security
import org.bouncycastle.jce.provider.{BouncyCastleProvider => JBouncyCastleProvider}
/**
 * This is a convenience trait that you can mix in to easily secure that the BouncyCastleProvider is installed.
 *
 * Created by marcusheese on 26/02/14.
 */
trait BouncyCastleProvider extends Logging {
  if(Security.getProvider(JBouncyCastleProvider.PROVIDER_NAME) == null) {
    logger.info("BouncyCastleProvider not installed yet. Doing that now!")
    try {
      val pos = Security.addProvider(new JBouncyCastleProvider)
      if(pos == -1) logger.warn("BouncyCastleProvider was already installed?!")
      else logger.info(s"BouncyCastleProvider added to the list of Security providers at position $pos")
    } catch {
      case e: Throwable => logger.error(s"Could not install BouncyCastleProvider: ${e.getMessage}")
    }
  } else {
    logger.info("BouncyCastleProvider is already installed.")
  }
}