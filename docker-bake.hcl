target "all" {
    targets = ["lrgs", "apps", "web-api", "migration"]
}

target "docker-metadata-action" {
   tags = ["latest"]
   platforms = ["linux/amd64", "linux/arm64"]
}

target "lrgs" {
    inherits = ["docker-metadata-action"]
    context = "."
    dockerfile = "Dockerfile"
    tags = [for tag in target.docker-metadata-action.tags : "ghcr.io/usace-watermanagement/opendcs-wm/opendcs-lrgs:${tag}"]
    target = "lrgs"
}

target "apps" {
    inherits = ["docker-metadata-action"]
    context = "."
    dockerfile = "Dockerfile"
    tags = [for tag in target.docker-metadata-action.tags : "ghcr.io/usace-watermanagement/opendcs-wm/opendcs-apps:${tag}"]
    target = "apps"
}

target "web-api" {
    inherits = ["docker-metadata-action"]
    context = "."
    dockerfile = "Dockerfile"
    tags = [for tag in target.docker-metadata-action.tags : "ghcr.io/usace-watermanagement/opendcs-wm/opendcs-api:${tag}"]
    target = "web-api"
}

target "migration" {
    inherits = ["docker-metadata-action"]
    context = "."
    dockerfile = "Dockerfile"
    tags = [for tag in target.docker-metadata-action.tags : "ghcr.io/usace-watermanagement/opendcs-wm/opendcs-migration:${tag}"]
    target = "migration"
}