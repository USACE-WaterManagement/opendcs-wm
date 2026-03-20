package opendcs

import (
	"encoding/json"
	"log"
)

type CompApp struct {
	AppId            int64             `json:"appId"`
	AppName          string            `json:"appName"`
	Comment          string            `json:"comment"`
	ManualEditingApp bool              `json:"manualEditingApp"`
	Properties       map[string]string `json:"properties"`
}

func GetActiveApps(profile Profile, env EnvironmentVars) []string {
	var ret []string

	var app = CreateApp(profile, env, "mil.usace.army.opendcs.support.ListCompApps")
	var output, err = app.WaitForOutput()

	// most likely reason this is nil is in the profile test case.
	if err == nil {
		log.Println("Got", output)

		var apps []CompApp
		err := json.Unmarshal([]byte(output), &apps)
		if err != nil {
			panic(err)
		}
		for _, app := range apps {
			log.Println(app)
			if app.Properties["cwbi-app"] == env.ApplicationName {
				ret = append(ret, app.AppName)
			}
		}
	} else {
		log.Println("Unable to get list of computation apps. Application did not start", err)
	}

	if len(ret) == 0 {
		ret = append(ret, env.ApplicationName) // nothing else configured default to the provided application name
	}

	return ret
}
