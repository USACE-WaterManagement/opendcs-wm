package opendcs

type TsdbApp struct {
	profile    Profile
	installDir string
	properties []string
	appClass   string
	classpath  string
	arguments  []string
}

func CreateApp(profile Profile, env EnvironmentVars) TsdbApp {
	var app = TsdbApp{profile, env.InstallDir, nil, "decodes.tsdb.ComputationApp", "", nill}

	return app
}

func (app *TsdbApp) Active() bool {
	return true
}
