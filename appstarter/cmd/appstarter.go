package main

import (
	_ "embed"
	"fmt"
	"io"
	"log"

	opendcs "github.com/usace-watermanagement/opendcs-watermanagement/appstarter/internal"
)

//go:embed decodes.properties.template
var profileTemplate string

func readLog(app *opendcs.TsdbApp) {
	stdout, stderr := app.GetOutput()

	for {
		var err error
		var buffer []byte
		_, err = stdout.Read(buffer)
		if err != nil {
			if err == io.EOF {
				break
			}
		}

		log.Printf("{'app': '%s/%s', 'level':'info','msg': '%s'}", app.Profile.Office, app.Profile.AppName, buffer)
		_, err = stderr.Read(buffer)
		if err != nil {
			if err == io.EOF {
				break
			}
		}

		log.Printf("{'app': '%s/%s', 'level':'error','msg': '%s'}", app.Profile.Office, app.Profile.AppName, buffer)
	}
}

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

	for _, appInstance := range apps {
		fmt.Printf("App %s/%s", appInstance.Profile.Office, appInstance.Profile.AppName)
		err := appInstance.Start()
		if err != nil {
			log.Fatal(err)
		}
		go readLog(appInstance)
	}

	for {
		for _, appInstance := range apps {
			if !appInstance.Active() {
				log.Printf("App %s/%s as died", appInstance.Profile.Office, appInstance.Profile.AppName)
			}
		}
	}
}
