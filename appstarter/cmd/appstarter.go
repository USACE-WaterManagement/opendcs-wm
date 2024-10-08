package main

import (
	_ "embed"
	"fmt"
	"log"
	"time"

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
	var apps []*opendcs.TsdbApp
	for _, profile := range profiles {
		apps = append(apps, opendcs.CreateApp(profile, myenv))

	}

	for {
		for _, appInstance := range apps {
			if !appInstance.Active() {
				log.Printf("App %s/%s has died", appInstance.Profile.Office, appInstance.Profile.AppName)
			}
		}
		time.Sleep(5000 * time.Second)
	}
}
