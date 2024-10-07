package opendcs

import (
	"io"
	"io/fs"
	"log"
	"os/exec"
	"path/filepath"
	"strings"
)

type TsdbApp struct {
	Profile    Profile
	installDir string
	properties []string
	appClass   string
	classPath  string
	arguments  []string
	handle     *exec.Cmd
}

func CreateApp(profile Profile, env EnvironmentVars) TsdbApp {
	var app = TsdbApp{profile, env.InstallDir, nil, "decodes.tsdb.ComputationApp",
		build_class_path(env.InstallDir, env.UserDir), nil, nil}

	return app
}

func build_class_path(dcsToolHome string, dcsToolUser string) string {
	var classpath strings.Builder

	addJars := func(path string, d fs.DirEntry, err error) error {
		if d.Type().IsRegular() && filepath.Ext(path) == ".jar" {
			if classpath.Len() != 0 {
				classpath.WriteString(":")
			}
			classpath.WriteString(path)
		}
		return nil
	}
	if err := filepath.WalkDir(dcsToolUser, addJars); err != nil {
		panic(err)
	}
	if err := filepath.WalkDir(dcsToolHome, addJars); err != nil {
		panic(err)
	}
	return classpath.String()
}

func (app *TsdbApp) Active() bool {
	return !app.handle.ProcessState.Exited()
}

func (app *TsdbApp) Start() {
	err := app.handle.Start()
	if err != nil {
		log.Fatal(err)
	}
}

func (app *TsdbApp) GetOutput() (io.ReadCloser, io.ReadCloser) {
	outPipe, err := app.handle.StdoutPipe()
	if err != nil {
		panic(err)
	}
	inPipe, err := app.handle.StderrPipe()
	if err != nil {
		panic(err)
	}
	return outPipe, inPipe
}
