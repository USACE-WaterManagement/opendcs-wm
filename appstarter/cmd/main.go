package main

import (
	_ "embed"
	"fmt"
	"log"
	"os/exec"

	opendcs "github.com/usace-watermanagement/opendcs-watermanagement/appstarter/internal"
)

//go:embed decodes.properties.template
var template string

func main() {
	fmt.Println("Hello, World!")
	cmd := exec.Command("echo", "Blah Blah Blah")

	result, err := cmd.Output()
	if err != nil {
		log.Print(err)
	}
	fmt.Printf("%s", result)

	myenv := opendcs.CurrentEnvironment()
	fmt.Printf("Type %s\n", myenv.DatabaseType)
	fmt.Printf("Env -> %s", myenv)
	profiles := opendcs.GetProfile(myenv, template)
	fmt.Println(profiles)
}
