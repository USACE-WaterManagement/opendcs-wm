package opendcs

import (
	_ "embed"
	"fmt"
	"log"
	"os"
	"text/template"
)

//go:embed decodes.properties.template
var ProfileTemplate string

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

	var tmpProf = GetProfile(environment, *tmpl, "utility")

	var activeApps = GetActiveApps(tmpProf, environment)

	for _, app := range activeApps {
		ret = append(ret, GetProfile(environment, *tmpl, app))
	}
	return ret
}

func GetProfile(environment EnvironmentVars, template template.Template, appName string) Profile {
	var office = environment.Office
	var profileFile = fmt.Sprintf("%s.profile", appName)
	file, err := os.OpenFile(profileFile, os.O_RDWR|os.O_CREATE, 0644)
	if err != nil {
		log.Fatal(err)
	}

	data := data{environment, office}

	err = template.Execute(file, data)
	if err != nil {
		panic(err)
	}
	if err := file.Close(); err != nil {
		log.Fatal(err)
	}
	return Profile{profileFile, appName, office}
}
