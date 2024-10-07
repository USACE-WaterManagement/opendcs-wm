package opendcs

import (
	"fmt"
	"io"
	"io/fs"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

type TsdbApp struct {
	Profile    Profile
	installDir string
	userDir    string
	appClass   string
	classPath  string
	arguments  []string
	handle     *exec.Cmd
}

func CreateApp(profile Profile, env EnvironmentVars) *TsdbApp {
	var app = TsdbApp{profile, env.InstallDir, env.UserDir, "decodes.tsdb.ComputationApp",
		build_class_path(env.InstallDir, env.UserDir), nil, nil}

	return &app
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
	if app.handle != nil {
		return app.handle.Process.Pid != 0
	}
	return false
}

func (app *TsdbApp) Start() error {
	var err error
	propsFile, err := os.OpenFile(fmt.Sprintf("args-%s-%s", app.Profile.Office, app.Profile.AppName),
		os.O_RDWR|os.O_CREATE, 0644)
	if err != nil {
		return err
	}

	propsFile.WriteString("-cp ")
	propsFile.WriteString(app.classPath)
	propsFile.WriteString("\n")
	propsFile.WriteString("-Xmx256m\n")
	propsFile.WriteString(fmt.Sprintf("-DDCSTOOL_HOME=%s\n", app.installDir))
	propsFile.WriteString(fmt.Sprintf("-DDCSTOOL_USERDIR=%s\n", app.userDir))
	propsFile.WriteString(fmt.Sprintf("-DDECODES_INSTALL_DIR=%s\n", app.installDir))

	propsFile.Close()
	javaPath, err := exec.LookPath("java")
	if err != nil {
		panic(err)
	}
	app.handle = exec.Command(javaPath, fmt.Sprintf("@%s", propsFile.Name()),
		app.appClass, "-P", app.Profile.ProfileFile, "-d3", "-l", "/dev/stdout")

	err = app.handle.Start()
	if err == nil {
		log.Print("App started")
	}
	return err
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
