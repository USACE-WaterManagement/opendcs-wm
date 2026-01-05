package main

import (
	"fmt"
	"log"
	"os"
	"time"

	"github.com/joho/godotenv"
	opendcs "github.com/usace-watermanagement/opendcs-watermanagement/appstarter/internal"
)

func main() {
	err := godotenv.Overload(".env")
	if err != nil {
		log.Printf("No .env file, assuming current environment is correct. %s", err)
	}
	appClass := os.Args[1]
	myenv := opendcs.CurrentEnvironment()
	fmt.Printf("Type -> %s\n", myenv.DatabaseType)
	fmt.Printf("Env -> %s\n", myenv)
	fmt.Printf("Main Class -> %s\n", appClass)
	profiles := opendcs.GetProfiles(myenv, opendcs.ProfileTemplate)
	fmt.Println(profiles)
	var apps []*opendcs.TsdbApp
	for _, profile := range profiles {
		apps = append(apps, opendcs.CreateApp(profile, myenv, appClass))

	}

	for {
		for i, appInstance := range apps {
			if !appInstance.Active() {
				log.Printf("App %s/%s has died", appInstance.Profile.Office, appInstance.Profile.AppName)
				apps = append(apps[:i], apps[i+1:]...)
			}
		}
		log.Printf("Have %d apps running\n", len(apps))
		if len(apps) == 0 {
			log.Fatal("All controlled applications have exited.")
			break
		}
		time.Sleep(5 * time.Second)
	}
}
