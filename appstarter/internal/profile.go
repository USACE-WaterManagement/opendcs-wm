package opendcs

import (
	"fmt"
	"log"
	"os"
	"text/template"
)

type Profile struct {
	ProfileFile string
	AppName     string
	Office      string
}

type data struct {
	Env    EnvironmentVars
	Office string
}

func GetProfiles(environment EnvironmentVars, profileTemplate string) []Profile {
	var ret []Profile
	var err error
	tmpl, err := template.New("test").Parse(profileTemplate)
	if err != nil {
		panic(err)
	}

	for _, office := range environment.Offices {
		var profileFile = fmt.Sprintf("%s.profile", office)
		file, err := os.OpenFile(profileFile, os.O_RDWR|os.O_CREATE, 0644)
		if err != nil {
			log.Fatal(err)
		}
		data := data{environment, office}
		err = tmpl.Execute(file, data)
		if err != nil {
			panic(err)
		}
		ret = append(ret, Profile{profileFile, environment.ApplicationName, office})

		if err := file.Close(); err != nil {
			log.Fatal(err)
		}

	}
	return ret
}
