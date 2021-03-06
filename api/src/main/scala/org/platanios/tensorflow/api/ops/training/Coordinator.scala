/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.api.ops.training

import org.platanios.tensorflow.api.ops.training.Coordinator.Event

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.{Condition, Lock, ReentrantLock}

import scala.collection.mutable

/**
  * @author Emmanouil Antonios Platanios
  */
case class Coordinator private[training]() {
  private[this] val logger   : Logger = Logger(LoggerFactory.getLogger("Coordinator"))
  private[this] val lock     : Lock   = new ReentrantLock
  private[this] val stopEvent: Event  = Event()

  /** Set to `true` if `join()` has already been called. */
  private[this] var joined: Boolean = false

  private[this] var causeForStop: Option[Throwable] = None

  /** Set of threads registered for joining when `join()` is called. These threads will be joined in addition to the
    * threads passed to the `join()` call. It is ok if threads are both registered and passed to the `join()` call. */
  private[this] val registeredThreads: mutable.Set[Thread] = mutable.Set.empty[Thread]

  /** Registers the provided thread to be managed by this coordinator.
    *
    * @param  thread Thread to register.
    */
  def register(thread: Thread): Unit = {
    lock.lock()
    try {
      registeredThreads += thread
    } finally {
      lock.unlock()
    }
  }

  /** Resets the state of this coordinator.
    *
    * Note that this method does not clear the set of managed threads of this coordinator (i.e., [[register]] does not
    * need to be called again).
    *
    * After this method is called, calls to [[shouldStop]] will return `false`. */
  def reset(): Unit = {
    lock.lock()
    try {
      joined = false
      causeForStop = None
      if (stopEvent.isSet)
        stopEvent.clear()
    } finally {
      lock.unlock()
    }
  }

  /** Returns `true` if a stop has been requested. */
  def shouldStop: Boolean = stopEvent.isSet

  /** Returns `true` if [[join]] has been called already. */
  def hasJoined: Boolean = joined

  /** Requests that all the threads stop running.
    *
    * After this method is called, calls to [[shouldStop]] will return `true`.
    *
    * Note: If a `cause` is being passed in, it must be in the context of handling the exception
    * (i.e. `try: ... except Exception as ex: ...`) and not a newly created one.
    *
    * @param  cause Optional cause for stopping. If this is the first call to [[requestStop]] with a provided cause,
    *               then that cause is recorded and is thrown from [[join]].
    */
  def requestStop(cause: Option[Throwable] = None): Unit = {
    lock.lock()
    try {
      // If we have already joined the coordinator the exception will not have a chance to be reported, so we just raise
      // it normally. This can happen if one continues to use a session after having joined the coordinator threads.
      if (joined)
        cause.foreach(throw _)
      if (!stopEvent.isSet) {
        if (cause.isDefined && causeForStop.isEmpty) {
          logger.info("Error reported to the coordinator.", cause)
          causeForStop = cause
        }
        stopEvent.set()
      }
    } finally {
      lock.unlock()
    }
  }

  /** Waits until the current coordinator is requested to stop. */
  def waitForStop(): Unit = stopEvent.waitUntilSet()

  /** Waits until the current coordinator is requested to stop.
    *
    * @param  timeout Sleep for up to `timeout` seconds waiting for `shouldStop` to become `true`.
    * @return `true`, if the wait was successful and the coordinator was requested to stop within the provided timeout,
    *         and `false` if the wait timed out.
    */
  def waitForStop(timeout: Double): Boolean = stopEvent.waitUntilSet(timeout)

  /** Waits for registered threads to terminate.
    *
    * This call blocks until a set of threads have terminated. The set of threads is the union of the threads passed in
    * the `threads` argument and the set of threads that registered with the coordinator by calling [[register]].
    *
    * After the threads stop, if a `cause` was passed to [[requestStop]], that cause is thrown.
    *
    * When [[requestStop]] is called, the threads are given `gracePeriod` seconds to terminate. If any of them is still
    * alive after that period expires and `ignoreLiveThreads` is `false`, a [[RuntimeException]] is thrown. Now that is
    * a `cause` was passed to [[requestStop]] then that cause is thrown instead of that [[RuntimeException]].
    *
    * @param  threads           Set of threads to join along with the registered threads.
    * @param  gracePeriod       Number of seconds given to threads to stop after [[requestStop]] has been called.
    * @param  ignoreLiveThreads If `false`, a [[RuntimeException]] is thrown is any of the threads are still alive after
    *                           the grace period expires. If `true`, the thread names are logged, but no exception is
    *                           thrown.
    * @throws RuntimeException If any of the threads are still alive after the grace period expires and
    *                          `ignoreLiveThreads` is set to `false`.
    */
  @throws[RuntimeException]
  def join(threads: Set[Thread] = Set.empty, gracePeriod: Int = 120, ignoreLiveThreads: Boolean = false): Unit = {
    // Threads registered after this call will not be joined.
    val allThreads = {
      lock.lock()
      try {
        (registeredThreads.toSet ++ threads).toSeq
      } finally {
        lock.unlock()
      }
    }

    // Wait for all threads to stop or for requestStop() to be called.
    while (allThreads.exists(_.isAlive) && !waitForStop(1.0)) {}

    // If any thread is still alive, wait for the grace period to expire. By the time this check is executed, threads
    // may still be shutting down, so we add a sleep of increasing duration to give them a chance to shut down without
    // losing too many cycles. Also, the sleep duration is limited to the remaining grace period duration.
    var remainingGracePeriod = gracePeriod / 1000
    var sleepTime = 1
    while (allThreads.exists(_.isAlive) && remainingGracePeriod >= 0.0) {
      Thread.sleep(sleepTime)
      remainingGracePeriod -= sleepTime
      // Keep the waiting period within sane bounds. The minimum value is to avoid decreasing sleepTime to a value that
      // could cause remainingGracePeriod to remain unchanged.
      sleepTime = Math.max(Math.min(2 * sleepTime, remainingGracePeriod), 1)
    }

    // List the threads that are still alive after the grace period.
    val stragglers = allThreads.filter(_.isAlive).map(_.getName)

    // Terminate with an exception, if appropriate.
    lock.lock()
    try {
      joined = true
      registeredThreads.clear()
      causeForStop.foreach(throw _)
      if (stragglers.nonEmpty) {
        if (ignoreLiveThreads)
          logger.info(s"The coordinator stopped with these threads still running: ${stragglers.mkString(" ")}.")
        else
          throw new RuntimeException(
            s"The coordinator stopped with these threads still running: ${stragglers.mkString(" ")}.")
      }
    } finally {
      lock.unlock()
    }
  }
}

private[training] object Coordinator {
  private[training] trait API {
    def coordinator(): Coordinator = Coordinator()
  }

  private[Coordinator] case class Event() {
    private[this] val lock: Lock      = new ReentrantLock
    private[this] val cond: Condition = lock.newCondition
    private[this] var flag: Boolean   = false

    @throws[InterruptedException]
    def waitUntilSet(): Unit = {
      lock.lock()
      try {
        while (!flag)
          cond.await()
      } finally {
        lock.unlock()
      }
    }

    @throws[InterruptedException]
    def waitUntilSet(timeout: Double): Boolean = {
      lock.lock()
      try {
        var timedOut = false
        while (!flag)
          timedOut = cond.await((timeout * 1000).toInt, TimeUnit.MILLISECONDS)
        timedOut
      } finally {
        lock.unlock()
      }
    }

    def isSet: Boolean = {
      lock.lock()
      try {
        flag
      } finally {
        lock.unlock()
      }
    }

    def set(): Unit = {
      lock.lock()
      try {
        flag = true
        cond.signalAll()
      } finally {
        lock.unlock()
      }
    }

    def clear(): Unit = {
      lock.lock()
      try {
        flag = false
        cond.signalAll()
      } finally {
        lock.unlock()
      }
    }
  }
}
