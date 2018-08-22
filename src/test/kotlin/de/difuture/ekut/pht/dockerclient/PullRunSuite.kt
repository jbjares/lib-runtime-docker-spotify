package de.difuture.ekut.pht.dockerclient

import org.junit.runners.Suite
import org.junit.runner.RunWith
import org.junit.runners.Suite.SuiteClasses


/**
 * This Suite simply ensures that the pull tests run before the run tests (if we can't pull the test images,
 * we do not need to continue).
 *
 * This does not mean that we do not pull in other classes.
 *
 * @
 *
 */
@SuiteClasses(DefaultDockerClientPullTests::class, DefaultDockerClientRunTests::class)
@RunWith(Suite::class)
class PullRunSuite
