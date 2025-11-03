package opendcs

import (
	"bufio"
	"encoding/json"
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
	active     bool
}

func CreateApp(profile Profile, env EnvironmentVars, appClass string) *TsdbApp {
	var app = TsdbApp{profile, env.InstallDir, env.UserDir, appClass,
		build_class_path(env.InstallDir, env.UserDir), nil, nil, true}
	if err := app.Start(); err != nil {
		panic(err)
	}
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
	return app.active
}

type LogMessage struct {
	App   string `json:"app"`
	Level string `json:"level"`
	Msg   string `json:"msg"`
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
	propsFile.WriteString(fmt.Sprintf("-DAPP_NAME=%s\n", app.Profile.AppName))
	propsFile.WriteString("-Dlogback.configurationFile=/opt/opendcs/logback.xml\n")
	propsFile.WriteString("-DLOG_LEVEL=INFO\n")

	propsFile.Close()
	javaPath, err := exec.LookPath("java")
	if err != nil {
		panic(err)
	}
	log.Printf("Found java at '%s'", javaPath)
	app.handle = exec.Command(javaPath, fmt.Sprintf("@%s", propsFile.Name()),
		app.appClass, "-P", app.Profile.ProfileFile,
		"-a", app.Profile.AppName)

	stdout, err := app.handle.StdoutPipe()
	if err != nil {
		panic(err)
	}

	stderr, err := app.handle.StderrPipe()
	if err != nil {
		panic(err)
	}

	go redirectPipe(stdout, app, "info")
	go redirectPipe(stderr, app, "error")
	log_message("info", app.Profile.AppName, app.Profile.Office, "Starting application")
	err = app.handle.Start()
	if err != nil {
		log.Fatal(err)
		return err
	}
	go func(app *TsdbApp) {
		err = app.handle.Wait()
		app.active = false
		if err != nil {
			log.Fatalf("App failed with %s", err)
		}
	}(app)

	log.Print("App started")
	return err
}

func redirectPipe(appPipe io.ReadCloser, app *TsdbApp, level string) {
	//from: https://stackoverflow.com/a/25191479
	// original reader was getting stuck
	buffer := bufio.NewScanner(appPipe)
	for buffer.Scan() {
		log.Println(buffer.Text()) // already using structured logging from application
	}
	log_message(level, app.Profile.AppName, app.Profile.Office, "Log output terminated.")
}

func log_message(level string, app string, office string, log_msg string) {
	msg := LogMessage{
		App:   fmt.Sprintf("%s/%s", office, app),
		Level: level,
		Msg:   log_msg,
	}
	out, err := json.Marshal(msg)
	if err != nil {
		log.Println("Error creating log message", err)
	}
	log.Println(string(out))
}
