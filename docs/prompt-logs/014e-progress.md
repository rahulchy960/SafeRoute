# P014e progress

One line per step: what is done, what is next. A new session resumes from `git log` and this file.

- Step 2 (databases): done. `HiltTestCleanup` rule in every Hilt test stops the app-lifetime scope and closes the in-memory database; tests without a named application get a plain one (`robolectric.properties`); `EmergencyActivityTest` became a Hilt test; one test keeps the real application. Next: step 1, real-time waits.
- Step 1 (real-time waits): done. `BackgroundWork` counts the app's background work in Hilt tests (`TestCoreModule`, Room executors) and is an Espresso idling resource; 33 polling waits became `compose.settled { }` or `Espresso.onIdle()`; `TestClock` can be stopped; `FakeApi.hold` replaces a sleep loop; the SMS gateway test runs in virtual time. Left as they are: `SignInToHomeTest` (real HTTP), `ApiErrorMappingTest` (a real socket timeout). The worker test moved to the Hilt class, so no test boots the real application. Next: step 4, five gate runs under UTC and en-US.
