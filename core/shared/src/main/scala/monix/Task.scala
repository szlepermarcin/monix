/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix

import monix.Task.{Callback, UnsafeCallback}
import monix.concurrent.{UncaughtExceptionReporter, CancelableFuture, Cancelable, Scheduler}
import monix.concurrent.cancelables._
import asterix.atomic.Atomic
import monix.internal.concurrent.TaskRunnable
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise, TimeoutException, CancellationException}
import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}


/** `Task` represents a specification for an asynchronous computation,
  * which when executed will produce an `A` as a result, along with
  * possible side-effects.
  *
  * Compared with `Future` from Scala's standard library, `Task` does
  * not represent a running computation, as `Task` does not execute
  * anything when working with its builders or operators and it does not
  * submit any work into any thread-pool, the execution eventually
  * taking place after `runAsync` is called and not before that.
  *
  * Note that `Task` is conservative in how it spawns logical threads.
  * Transformations like `map` and `flatMap` for example will default
  * to being executed on the logical thread on which the asynchronous
  * computation was started. But one shouldn't make assumptions about
  * how things will end up executed, as ultimately it is the
  * implementation's job to decide on the best execution model. All
  * you are guaranteed is asynchronous execution after executing `runAsync`.
  */
sealed abstract class Task[+T] { self =>
  /** Characteristic function for our [[Task]]. Never use this directly.
    *
    * @param scheduler is the [[Scheduler]] under that the `Task` will use to
    *                  fork threads, schedule with delay and to report errors
    * @param cancelable is a [[MultiAssignmentCancelable]] that can either be used
    *                   to check if the task is canceled or can be assigned to something
    *                   that can eventually cancel the running computation
    * @param stackDepth represents the current stack depth, taking into account
    *                   this call as well
    * @param callback is the pair of `onSuccess` and `onError` methods that will
    *                 be called when the execution completes
    *
    * @return a [[Cancelable]] that can be used to cancel the running computation
    */
  protected def unsafeRunFn(
    scheduler: Scheduler,
    cancelable: MultiAssignmentCancelable,
    stackDepth: Int,
    callback: UnsafeCallback[T]): Unit

  /** Internal utility providing a stack-safe `unsafeExecuteFn`, to be used
    * when constructing operators.
    *
    * @param scheduler is the [[Scheduler]] under that the `Task` will use to
    *                  fork threads, schedule with delay and to report errors
    * @param stackDepth represents the current stack depth, taking into account
    *                   this call as well
    * @param cancelable is a [[MultiAssignmentCancelable]] that can either be used
    *                   to check if the task is canceled or can be assigned to something
    *                   that can eventually cancel the running computation
    * @param callback is the pair of `onSuccess` and `onError` methods that will
    *                 be called when the execution completes
    *
    * @return a [[Cancelable]] that can be used to cancel the running computation
    */
  private[monix] def stackSafeRun(
    scheduler: Scheduler,
    cancelable: MultiAssignmentCancelable,
    stackDepth: Int,
    callback: UnsafeCallback[T]): Unit = {

    if (stackDepth > 0 && stackDepth < Scheduler.recommendedBatchSize)
      try unsafeRunFn(scheduler, cancelable, stackDepth+1, callback) catch {
        case NonFatal(ex) =>
          callback.safeOnError(scheduler, stackDepth, ex)
      }
    else {
      cancelable := scheduler.scheduleOnce(new Runnable {
        override def run(): Unit =
          self.unsafeRunFn(scheduler, cancelable, stackDepth = 1, callback)
      })
    }
  }

  /** Triggers the asynchronous execution.
    *
    * @param cb is a callback that will be invoked upon completion.
    * @return a [[Cancelable]] that can be used to cancel a running task
    */
  def runAsync(cb: Task.Callback[T])(implicit s: Scheduler): Cancelable = {
    val cancelable = MultiAssignmentCancelable()
    val safe = Task.Callback.safe(cb)(s)

    stackSafeRun(s, cancelable, -1, new UnsafeCallback[T] {
      def onSuccess(value: T, stackDepth: Int) = safe.onSuccess(value)
      def onError(ex: Throwable, stackDepth: Int) = safe.onError(ex)
    })

    cancelable
  }

  /** Triggers the asynchronous execution.
    *
    * @param f is a callback that will be invoked upon completion.
    * @return a [[Cancelable]] that can be used to cancel a running task
    */
  def runAsync(f: Try[T] => Unit)(implicit s: Scheduler): Cancelable =
    runAsync(new Callback[T] {
      def onSuccess(value: T): Unit = f(Success(value))
      def onError(ex: Throwable): Unit = f(Failure(ex))
    })

  /** Triggers the asynchronous execution.
    *
    * @return a [[CancelableFuture]] that can be used to extract the result
    *         or to cancel a running task. In case it is canceled, then the
    *         future completes with a `CancellationException`.
    */
  def runAsync(implicit s: Scheduler): CancelableFuture[T] = {
    val p = Promise[T]()
    val cancelable = runAsync(new Callback[T] {
      def onSuccess(value: T): Unit = p.trySuccess(value)
      def onError(ex: Throwable): Unit = p.tryFailure(ex)
    })

    val withTrigger = Cancelable {
      try cancelable.cancel() finally
        p.tryFailure(new CancellationException("CancelableFuture.cancel"))
    }

    CancelableFuture(p.future, withTrigger)
  }

  /** Returns a new Task that applies the mapping function to
    * the element emitted by the source.
    */
  def map[U](f: T => U): Task[U] =
    Task.unsafeCreate[U] { (scheduler, cancelable, depth, cb) =>
      self.stackSafeRun(scheduler, cancelable, depth, new UnsafeCallback[T] {
        def onError(ex: Throwable, depth: Int): Unit =
          cb.safeOnError(scheduler, depth, ex)

        def onSuccess(value: T, depth: Int): Unit = {
          var streamError = true
          try {
            val u = f(value)
            streamError = false
            cb.safeOnSuccess(scheduler, depth, u)
          } catch {
            case NonFatal(ex) if streamError =>
              cb.safeOnError(scheduler, depth, ex)
          }
        }
      })
    }

  /** Given a source Task that emits another Task, this function flattens the result,
    * returning a Task equivalent to the emitted Task by the source.
    */
  def flatten[U](implicit ev: T <:< Task[U]): Task[U] =
    flatMap(t => t)

  /** Creates a new Task by applying a function to the successful
    * result of the source Task, and returns a task equivalent to
    * the result of the function.
    */
  def flatMap[U](f: T => Task[U]): Task[U] =
    Task.unsafeCreate { (scheduler, cancelable, depth, cb) =>
      self.stackSafeRun(scheduler, cancelable, depth, new UnsafeCallback[T] {
        def onError(ex: Throwable, depth: Int): Unit =
          cb.safeOnError(scheduler, depth, ex)

        def onSuccess(value: T, depth: Int): Unit = {
          var streamError = true
          try {
            val taskU = f(value)
            streamError = false
            taskU.stackSafeRun(scheduler, cancelable, depth, cb)
          }
          catch {
            case NonFatal(ex) if streamError =>
              cb.safeOnError(scheduler, depth, ex)
          }
        }
      })
    }

  /** Returns a task that waits for the specified `timespan` before
    * executing and mirroring the result of the source.
    */
  def delay(timespan: FiniteDuration): Task[T] =
    Task.unsafeCreate[T] { (scheduler, cancelable, depth, cb) =>
      // delaying execution
      cancelable := scheduler.scheduleOnce(timespan.length, timespan.unit,
        new Runnable {
          override def run(): Unit = {
            self.stackSafeRun(scheduler, cancelable, depth, cb)
          }
        })
    }

  /** Returns a failed projection of this task.
    *
    * The failed projection is a future holding a value of type `Throwable`,
    * emitting a value which is the throwable of the original task in
    * case the original task fails, otherwise if the source succeeds, then
    * it fails with a `NoSuchElementException`.
    */
  def failed: Task[Throwable] =
    Task.unsafeCreate { (scheduler, cancelable, depth, cb) =>
      self.stackSafeRun(scheduler, cancelable, depth, new UnsafeCallback[T] {
        def onError(ex: Throwable, depth: Int): Unit =
          cb.safeOnSuccess(scheduler, depth, ex)
        def onSuccess(value: T, depth: Int): Unit =
          cb.safeOnError(scheduler, depth, new NoSuchElementException("Task.failed"))
      })
    }

  /** Creates a new task that will handle any matching throwable
    * that this task might emit.
    */
  def onErrorRecover[U >: T](pf: PartialFunction[Throwable, U]): Task[U] =
    Task.unsafeCreate { (scheduler, cancelable, depth, cb) =>
      self.stackSafeRun(scheduler, cancelable, depth, new UnsafeCallback[T] {
        def onSuccess(v: T, depth: Int): Unit =
          cb.safeOnSuccess(scheduler, depth, v)

        def onError(ex: Throwable, depth: Int) =
          try {
            if (pf.isDefinedAt(ex))
              cb.safeOnSuccess(scheduler, depth, pf(ex))
            else
              cb.safeOnError(scheduler, depth, ex)
          } catch {
            case NonFatal(err) =>
              scheduler.reportFailure(ex)
              cb.safeOnError(scheduler, depth, err)
          }
      })
    }

  /** Creates a new task that will handle any matching throwable that this
    * task might emit by executing another task.
    */
  def onErrorRecoverWith[U >: T](pf: PartialFunction[Throwable, Task[U]]): Task[U] =
    Task.unsafeCreate { (scheduler, cancelable, depth, cb) =>
      self.stackSafeRun(scheduler, cancelable, depth, new UnsafeCallback[T] {
        def onSuccess(v: T, depth: Int) =
          cb.safeOnSuccess(scheduler, depth, v)

        def onError(ex: Throwable, depth: Int): Unit =
          try {
            if (pf.isDefinedAt(ex)) {
              val newTask = pf(ex)
              newTask.stackSafeRun(scheduler, cancelable, depth, cb)
            }
            else
              cb.safeOnError(scheduler, depth, ex)
          } catch {
            case NonFatal(err) =>
              scheduler.reportFailure(ex)
              cb.safeOnError(scheduler, depth, err)
          }
      })
    }

  /** Returns a Task that mirrors the source Task but that triggers a
    * `TimeoutException` in case the given duration passes without the
    * task emitting any item.
    */
  def timeout(after: FiniteDuration): Task[T] =
    Task.unsafeCreate { (scheduler, cancelable, depth, cb) =>
      val activeTask = MultiAssignmentCancelable()
      val timeoutTask = MultiAssignmentCancelable()

      val composite = CompositeCancelable(timeoutTask, activeTask)
      cancelable := composite

      timeoutTask := scheduler.scheduleOnce(after.length, after.unit,
        new Runnable {
          def run(): Unit =
            if (composite.cancel()) {
              val ex = new TimeoutException(s"Task timed-out after $after of inactivity")
              cb.safeOnError(scheduler, depth, ex)
            }
        })

      self.stackSafeRun(scheduler, activeTask, depth, new UnsafeCallback[T] {
        def onSuccess(v: T, depth: Int): Unit =
          if (timeoutTask.cancel()) {
            cancelable := activeTask
            cb.safeOnSuccess(scheduler, depth, v)
          }

        def onError(ex: Throwable, depth: Int): Unit =
          if (timeoutTask.cancel()) {
            cancelable := activeTask
            cb.safeOnError(scheduler, depth, ex)
          }
      })
    }

  /** Returns a Task that mirrors the source Task but switches to
    * the given backup Task in case the given duration passes without the
    * source emitting any item.
    */
  def timeout[U >: T](after: FiniteDuration, backup: Task[U]): Task[U] =
    Task.unsafeCreate { (scheduler, cancelable, depth, cb) =>
      val activeTask = MultiAssignmentCancelable()
      val timeoutTask = MultiAssignmentCancelable()

      val composite = CompositeCancelable(timeoutTask, activeTask)
      cancelable := composite

      timeoutTask := scheduler.scheduleOnce(after.length, after.unit,
        new Runnable {
          def run(): Unit =
            if (timeoutTask.cancel()) {
              composite.cancel() // gc purposes
              backup.stackSafeRun(scheduler, cancelable, depth, cb)
            }
        })

      self.stackSafeRun(scheduler, activeTask, depth, new UnsafeCallback[T] {
        def onSuccess(v: T, depth: Int): Unit =
          if (timeoutTask.cancel()) {
            cancelable := activeTask
            cb.safeOnSuccess(scheduler, depth, v)
          }

        def onError(ex: Throwable, depth: Int): Unit =
          if (timeoutTask.cancel()) {
            cancelable := activeTask
            cb.safeOnError(scheduler, depth, ex)
          }
      })
    }

  /** Zips the values of `this` and `that` task, and creates a new task that
    * will emit the tuple of their results.
    */
  def zip[U](that: Task[U]): Task[(T, U)] =
    Task.unsafeCreate { (scheduler, cancelable, depth, cb) =>
      val state = Atomic(null : Either[T, U])

      val thisTask = MultiAssignmentCancelable()
      val thatTask = MultiAssignmentCancelable()
      val composite = CompositeCancelable(thisTask, thatTask)
      cancelable := composite

      self.stackSafeRun(scheduler, thisTask, depth, new UnsafeCallback[T] {
        def onError(ex: Throwable, depth: Int): Unit =
          if (composite.cancel()) cb.safeOnError(scheduler, depth, ex)

        @tailrec def onSuccess(t: T, depth: Int): Unit =
          state.get match {
            case null =>
              if (!state.compareAndSet(null, Left(t))) onSuccess(t, depth)
            case Right(u) =>
              cb.safeOnSuccess(scheduler, depth, (t, u))
            case Left(_) =>
              ()
          }
      })

      that.stackSafeRun(scheduler, thatTask, depth, new UnsafeCallback[U] {
        def onError(ex: Throwable, depth: Int): Unit =
          if (composite.cancel()) cb.safeOnError(scheduler, depth, ex)

        @tailrec def onSuccess(u: U, depth: Int): Unit =
          state.get match {
            case null =>
              if (!state.compareAndSet(null, Right(u))) onSuccess(u, depth)
            case Left(t) =>
              cb.safeOnSuccess(scheduler, depth, (t, u))
            case Right(_) =>
              ()
          }
      })
    }
}

object Task {
  /** Returns a new task that, when executed, will emit the
    * result of the given function executed asynchronously.
    */
  def apply[T](f: => T): Task[T] =
    Task.unsafeCreate { (scheduler, cancelable, depth, callback) =>
      if (!cancelable.isCanceled) {
        // protecting against user code errors
        var streamErrors = true
        try {
          val result = f
          streamErrors = false
          callback.safeOnSuccess(scheduler, depth, result)
        } catch {
          case NonFatal(ex) =>
            if (streamErrors) // failure was user code, so stream it
              callback.safeOnError(scheduler, depth, ex)
            else // failure is in Monix
              scheduler.reportFailure(ex)
        }
      }
    }

  /** Returns a `Task` that on execution is always successful, emitting
    * the given strict value.
    */
  def now[T](elem: T): Task[T] =
    new Now(elem)

  /** Returns a task that on execution is always finishing in error
    * emitting the specified exception.
    */
  def error(ex: Throwable): Task[Nothing] =
    new Fail(ex)

  /** Create a `Task` from an asynchronous computation, which takes the form
    * of a function with which we can register a callback. This can be used
    * to translate from a callback-based API to a straightforward monadic
    * version.
    *
    * @param register is a function that will be called when this `Task` is
    *                 executed, receiving a callback as a parameter,
    *                 a callback that the user is supposed to call in order
    *                 to signal the desired outcome of this `Task`.
   */
  def create[T](register: (Callback[T], Scheduler) => Cancelable): Task[T] =
    Task.unsafeCreate { (scheduler, cancelable, depth, cb) =>
      var streamErrors = true
      try {
        val callback = new Callback[T] {
          def onSuccess(value: T) =
            cb.safeOnSuccess(scheduler, depth, value)
          def onError(ex: Throwable): Unit =
            cb.safeOnError(scheduler, depth, ex)
        }

        val c = register(callback, scheduler)
        streamErrors = false
        cancelable := c
      } catch {
        case NonFatal(ex) =>
          if (streamErrors) // failure is in user code
            cb.safeOnError(scheduler, depth, ex)
          else // failure was us
            scheduler.reportFailure(ex)
      }
    }

  /** Converts the given Scala `Future` into a `Task` */
  def fromFuture[T](f: => Future[T]): Task[T] =
    Task.unsafeCreate { (scheduler, cancelable, depth, callback) =>
      f.onComplete {
        case Success(value) =>
          if (!cancelable.isCanceled)
            callback.safeOnSuccess(scheduler, depth, value)
        case Failure(ex) =>
          if (!cancelable.isCanceled)
            callback.safeOnError(scheduler, depth, ex)
      }(scheduler)
    }

  /** Builder for [[Task]] instances. For usage on implementing
    * operators or builders. Internal to Monix.
    */
  private def unsafeCreate[T](f: (Scheduler, MultiAssignmentCancelable, Int, UnsafeCallback[T]) => Unit): Task[T] =
    new Task[T] {
      def unsafeRunFn(s: Scheduler, c: MultiAssignmentCancelable, d: Int, cb: UnsafeCallback[T]): Unit =
        f(s, c, d, cb)
    }

  /** Represents a callback that should be called asynchronously,
    * having the execution managed by the given `scheduler`.
    * Used by [[Task]] to signal the completion of asynchronous
    * computations.
    *
    * The `onSuccess` method should be called only once, with the successful
    * result of our asynchronous computation, whereas `onError` should be called
    * if the result is an error.
    */
  abstract class Callback[-T] {
    def onSuccess(value: T): Unit
    def onError(ex: Throwable): Unit
  }

  private object Callback {
    /** Wraps any [[Callback]] into a [[SafeCallback]].
      * For usage in `runAsync`.
      */
    def safe[T](cb: Callback[T])(implicit r: UncaughtExceptionReporter): Callback[T] =
      cb match {
        case _: SafeCallback[_] => cb
        case _ => new SafeCallback[T](cb)
      }
  }

  /** A `SafeCallback` is a callback that ensures it can only
    * be called once, with a simple check.
    */
  private class SafeCallback[-T]
    (underlying: Callback[T])(implicit r: UncaughtExceptionReporter)
    extends Callback[T] {

    private[this] var isActive = true

    /** To be called only once, on successful completion of a [[Task]] */
    def onSuccess(value: T): Unit =
      if (isActive) {
        isActive = false
        try underlying.onSuccess(value) catch {
          case NonFatal(ex) =>
            r.reportFailure(ex)
        }
      }

    /** To be called only once, on failure of a [[Task]] */
    def onError(ex: Throwable): Unit =
      if (isActive) {
        isActive = false
        try underlying.onError(ex) catch {
          case NonFatal(err) =>
            r.reportFailure(ex)
            r.reportFailure(err)
        }
      }
  }

  /** Not meant for usage, unless you know what you're doing.
    * Used in the implementation of [[Task]].
    *
    * Represents a callback that should be called with the result
    * of the asynchronous computation.
    */
  abstract class UnsafeCallback[-T] { self =>
    /** To be called only once, on successful completion of a [[Task]].
      *
      * @param value is the successful result to signal
      * @param stackDepth is the current stack depth (this call included)
      */
    def onSuccess(value: T, stackDepth: Int): Unit

    /** To be called only once, on failure of a [[Task]].
      *
      * @param ex is the failure
      * @param stackDepth is the current stack depth (this call included)
      */
    def onError(ex: Throwable, stackDepth: Int): Unit

    /** Wraps [[onSuccess]] in a safe function that, in case the stack depth
      * has exceeded the allowed maximum, forks a new logical thread
      * on the given scheduler.
      *
      * @param s is the scheduler on top of which we'll fork, if needed
      * @param value is the successful result to signal
      * @param stackDepth is the current stack depth (this call included)
      */
    def safeOnSuccess(s: Scheduler, stackDepth: Int, value: T): Unit = {
      if (stackDepth < Scheduler.recommendedBatchSize)
        try onSuccess(value, stackDepth+1) catch {
          case NonFatal(ex) =>
            safeOnError(s, stackDepth, ex)
        }
      else
        s.execute(TaskRunnable.AsyncOnSuccess(self, value))
    }

    /** Wraps [[onError]] in a safe function that, in case the stack depth
      * has exceeded the allowed maximum, forks a new logical thread
      * on the given scheduler.
      *
      * @param s is the scheduler on top of which we'll fork, if needed
      * @param ex is the failure
      * @param stackDepth is the current stack depth (this call included)
      */
    def safeOnError(s: Scheduler, stackDepth: Int, ex: Throwable): Unit = {
      if (stackDepth < Scheduler.recommendedBatchSize)
        onError(ex, stackDepth+1)
      else
        s.execute(TaskRunnable.AsyncOnError(self, ex))
    }
  }

  /** Optimized task for already known strict values.
    * Internal to Monix, not for public consumption.
    *
    * See [[Task.now]] instead.
    */
  private final class Now[+T](value: T) extends Task[T] {
    def unsafeRunFn(s: Scheduler, c: MultiAssignmentCancelable, d: Int, cb: UnsafeCallback[T]): Unit =
      cb.safeOnSuccess(s, d, value)

    override def runAsync(implicit s: Scheduler): CancelableFuture[T] =
      CancelableFuture(Future.successful(value), Cancelable.empty)
  }

  /** Optimized task for failed outcomes.
    * Internal to Monix, not for public consumption.
    *
    * See [[Task.error]] instead.
    */
  private final class Fail(ex: Throwable) extends Task[Nothing] {
    def unsafeRunFn(s: Scheduler, c: MultiAssignmentCancelable, d: Int, cb: UnsafeCallback[Nothing]): Unit =
      cb.safeOnError(s, d, ex)

    override def runAsync(implicit s: Scheduler): CancelableFuture[Nothing] =
      CancelableFuture(Future.failed(ex), Cancelable.empty)
  }
}
