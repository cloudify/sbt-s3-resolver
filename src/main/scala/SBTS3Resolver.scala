package ohnosequences.sbt

import sbt._
import com.amazonaws.services.s3.model.Region
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.AmazonClientException
import scala.tools.nsc.io.File

object SbtS3Resolver extends Plugin {

  private lazy val defaultAWSCredentialsProviderChain = new DefaultAWSCredentialsProviderChain()

  type S3Credentials = (String, String)

  private lazy val fileCredentials =
    SettingKey[Option[S3Credentials]]("AWS credentials from s3-credentials-file")

  private lazy val defaultChainCredentials =
    SettingKey[Option[S3Credentials]]("AWS credentials from instance profile")

  lazy val s3credentialsFile = 
    SettingKey[Option[String]]("s3-credentials-file", 
      "properties format file with amazon credentials to access S3")

  lazy val s3credentials = 
    SettingKey[Option[S3Credentials]]("s3-credentials", 
      "S3 credentials accessKey and secretKey")

  // parsing credentials from the file
  def s3credentialsParser(file: Option[String]): Option[S3Credentials] = {

    file map { f: String =>
      val path = new java.io.File(f)
      val p = new java.util.Properties
      p.load(new java.io.FileInputStream(path))
      ( p.getProperty("accessKey")
      , p.getProperty("secretKey") )
    }

  }

  // convenience method, to use normal bucket addresses with `at`
  def toHttp(bucket: String): String = 
    if(bucket.startsWith("s3://"))
       "http://"+bucket.stripPrefix("s3://")+".s3.amazonaws.com"
    else bucket

  case class S3Resolver(
      name: String
    , url: String
    , patterns: Patterns = Resolver.defaultPatterns
    , overwrite: Boolean = false
    , region: Region = Region.EU_Ireland
    ) {

    // for proper serialization
    override def toString = 
      """S3Resolver(\"%s\", \"%s\", %s)""" format 
        (name, url, patternsToString(patterns))

    private def patternsToString(ps: Patterns): String =
      "Patterns(%s, %s, %s)" format (
        seqToString(ps.ivyPatterns)
      , seqToString(ps.artifactPatterns)
      , ps.isMavenCompatible
      )

    private def seqToString(s: Seq[String]): String = 
      s.mkString("Seq(\\\"", "\\\", \\\"", "\\\")")


    // setting up normal sbt resolver depending on credentials
    def toSbtResolver(credentials: S3Credentials): Resolver = {

      val r = new ohnosequences.ivy.S3Resolver(
          name
        , credentials._1 //accessKey
        , credentials._2 //secretKey
        , overwrite
        , region
        )

      if (patterns.isMavenCompatible) r.setM2compatible(true)

      def withBase(pattern: String): String = 
        if(url.endsWith("/") || pattern.startsWith("/")) url + pattern 
        else url + "/" + pattern

      patterns.ivyPatterns.foreach{ p => r.addIvyPattern(withBase(p)) }
      patterns.artifactPatterns.foreach{ p => r.addArtifactPattern(withBase(p)) }

      new sbt.RawRepository(r)

    }

  }

  // default values
  override def settings = Seq(

    s3credentialsFile := None

    /**
     * Attempts to read the credentials from the `s3credentialsFile` if set
     */
  , fileCredentials <<= s3credentialsFile { pathOpt =>
      val validPath = pathOpt.filter { path =>
        File(path).canRead
      }
      s3credentialsParser(validPath)
    }

    /**
     * Attempts for fetch the credentials using the `DefaultAWSCredentialsProviderChain`
     * class provided by the AWS SDK
     */
  , defaultChainCredentials := {
      try {
        val awsCredentials = defaultAWSCredentialsProviderChain.getCredentials
        Some((awsCredentials.getAWSAccessKeyId, awsCredentials.getAWSSecretKey))
      } catch {
        case e: AmazonClientException => None
      }
    }

    /**
     * Gets the credentials from `s3credentialsFile`, falling back to
     * `defaultChainCredentials`
     */
  , s3credentials <<= (fileCredentials, defaultChainCredentials) { (fc, cc) =>
      fc orElse cc
    }
  )
} 
