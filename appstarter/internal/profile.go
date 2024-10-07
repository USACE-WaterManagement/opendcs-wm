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

func GetProfile(environment EnvironmentVars, profileTemplate string) []Profile {
	var ret []Profile

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
		data := map[string]interface{}{
			"env":    environment,
			"office": office,
		}
		tmpl.Execute(file, data)
		ret = append(ret, Profile{profileFile, environment.ApplicationName, office})

		if err := file.Close(); err != nil {
			log.Fatal(err)
		}

	}
	return ret
}
