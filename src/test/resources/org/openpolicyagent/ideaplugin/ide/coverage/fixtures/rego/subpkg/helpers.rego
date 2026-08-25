# Second file in the fixture, so a generated report covers more than one path and exercises
# subdirectory remapping. Referenced from policy.rego, so the planner compiles it.
package example.subpkg

known_user if {
	input.user == "alice"
}
