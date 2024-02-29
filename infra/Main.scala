import besom.*
import besom.api.gcp
import besom.api.docker

@main def main = Pulumi.run {
  val project = config.requireString("gcp:project")
  val region = config.requireString("gcp:region")

  val repoName = p"gcr.io/${project}/${pulumiProject}" // will be automatically created by GCP on docker push
  val appName = "app"
  val imageFullName = p"${repoName}/${appName}:latest"

  enum GoogleApis(val name: String):
    case CloudRun extends GoogleApis("run.googleapis.com")
    def enableApiKey: NonEmptyString = s"enable-${name.replace(".", "-")}"

  // Enable GCP service(s) for the current project
  val enableServices: Map[GoogleApis, Output[gcp.projects.Service]] = List(
    GoogleApis.CloudRun
  ).map(api =>
    api -> gcp.projects.Service(
      api.enableApiKey,
      gcp.projects.ServiceArgs(
        project = project,
        service = api.name,
        /* if true - at every destroy this will disable the dependent services for the whole project */
        disableDependentServices = true,
        /* if true - at every destroy this will disable the service for the whole project */
        disableOnDestroy = true
      )
    )
  ).toMap

  // Build a Docker image from our Scala app and push it to GCR
  val image = docker.Image(
    "image",
    docker.ImageArgs(
      imageName = imageFullName,
      build = docker.inputs.DockerBuildArgs(
        context = p"../${appName}",
        platform = "linux/amd64" // Cloud Run only supports linux/amd64
      )
    )
  )

  // Deploy to Cloud Run. Some additional parameters such as concurrency and memory are set for illustration purposes.
  val service = gcp.cloudrun.Service(
    "service",
    gcp.cloudrun.ServiceArgs(
      location = region,
      name = appName,
      template = gcp.cloudrun.inputs.ServiceTemplateArgs(
        spec = gcp.cloudrun.inputs.ServiceTemplateSpecArgs(
          containers = gcp.cloudrun.inputs.ServiceTemplateSpecContainerArgs(
            image = image.imageName,
            resources = gcp.cloudrun.inputs.ServiceTemplateSpecContainerResourcesArgs(
              limits = Map(
                "memory" -> "1Gi"
              )
            )
          ) :: Nil
        )
      )
    ),
    opts(dependsOn = enableServices(GoogleApis.CloudRun))
  )

  // Open the service to public unrestricted access
  val serviceIam = gcp.cloudrun.IamMember(
    "service-iam-everyone",
    gcp.cloudrun.IamMemberArgs(
      location = service.location,
      service = service.name,
      role = "roles/run.invoker",
      member = "allUsers"
    )
  )

  Stack(
    Output.sequence(enableServices.values),
    serviceIam
  ).exports(
    dockerImage = imageFullName,
    serviceUrl = service.statuses.map(_.headOption.map(_.url)) // Export the DNS name of the service
  )
}
