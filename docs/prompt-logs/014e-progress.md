# P014e progress

One line per step: what is done, what is next. A new session resumes from `git log` and this file.

- Step 2 (databases): done. `HiltTestCleanup` rule in every Hilt test stops the app-lifetime scope and closes the in-memory database; tests without a named application get a plain one (`robolectric.properties`); `EmergencyActivityTest` became a Hilt test; one test keeps the real application. Next: step 1, real-time waits.
