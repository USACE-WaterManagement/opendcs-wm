package opendcs

import (
	"os"
	"testing"
)

func TestProfiles(t *testing.T) {
	env := EnvironmentVars{"XML", "jdbc:test", "noop", "org.opendcs.testing", []string{"SPK", "LRL", "SPA"},
		"CWMS", "fake", "testApp", "/tmp/installDir", "/tmp/userDir"}

	profiles := GetProfiles(env, ProfileTemplate)

	if len(profiles) != 3 {
		t.Fatalf("Not all profiles created. Only %d", len(profiles))
	}

	for _, profile := range profiles {
		if profile.AppName != "testApp" {
			t.Fatal("App name not set correctly.")
		}

		_, err := os.ReadFile(profile.ProfileFile)
		if err != nil {
			t.Fatalf("Profile not created %s", err)
		}
		os.Remove(profile.ProfileFile)
	}

}
