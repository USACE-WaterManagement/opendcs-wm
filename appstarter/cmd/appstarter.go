package main

import (
	_ "embed"
	"fmt"

	opendcs "github.com/usace-watermanagement/opendcs-watermanagement/appstarter/internal"
)

//go:embed decodes.properties.template
var profileTemplate string

func main() {
	myenv := opendcs.CurrentEnvironment()
	fmt.Printf("Type %s\n", myenv.DatabaseType)
	fmt.Printf("Env -> %s", myenv)
	profiles := opendcs.GetProfiles(myenv, profileTemplate)
	fmt.Println(profiles)
	var apps []opendcs.TsdbApp
	for _, profile := range profiles {
		apps = append(apps, opendcs.CreateApp(profile, myenv))

	}

	for _, appInstance := range apps {
		fmt.Printf("App %s/%s", appInstance.Profile.Office, appInstance.Profile.AppName)
		fmt.Println(appInstance)
	}
}
