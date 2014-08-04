TaskKey[Unit]("javah-finder") <<= (baseDirectory, streams) map { (b,s) =>
  val headers = (b ** "*.h").get
  s.log.info("Headers: " + (headers mkString ","))
}
