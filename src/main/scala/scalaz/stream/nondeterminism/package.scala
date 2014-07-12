package scalaz.stream

import scala.util.Try
import scalaz._
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.stream.Process._

/**
 * Created by pach on 04/07/14.
 */
package object nondeterminism {


  /**
   * Non-deterministic join of streams. Streams are joined non deterministically.
   * Whenever one of the streams terminates with other reason than `End` or `Kill`
   * the whole join terminates with that reason and all
   * remaining input processes are terminated with `Kill` cause as well.
   *
   * If the resulting process is killed or fails, all the merged processes terminates with `Kill` cause,
   * in case downstream terminates normally the upstream processes are terminated with `Kill` cause as well.
   *
   * @param maxOpen     maximum simultaneous processes that concurrently merge. If eq 0 no limit.
   * @param maxQueued   maximum number of queued `A` before merged processes will back-off. If eq 0 no limit.
   * @param source
   * @param S
   * @tparam A
   * @return
   */
  def njoin[A](maxOpen: Int, maxQueued: Int)(source: Process[Task, Process[Task, A]])(implicit S: Strategy): Process[Task, A] = {
    sealed trait M
    case class Offer(p: Process[Task, A], next: Cause => Process[Task, Process[Task, A]]) extends M
    case class FinishedSource(rsn: Cause) extends M
    case class Finished(result: Throwable \/ Unit) extends M
    case class FinishedDown(cb: (Throwable \/ Unit) => Unit) extends M


    suspend {
      val q = async.boundedQueue[A](maxQueued)(S)
      val done = async.signal[Boolean](S)

      //keep state of master source
      var state: Either3[Cause, Cause => Unit, Cause => Process[Task, Process[Task, A]]] =
        Either3.middle3((_: Cause) => ())

      //keep no of open processes
      var opened: Int = 0

      var actor: Actor[M] = null

      //evaluates next step of the source of processes
      def nextStep(from: Process[Task, Process[Task, A]]) =
        Either3.middle3({
          from.runAsync({
            case -\/(rsn) =>
              actor ! FinishedSource(rsn)

            case \/-((processes, next)) =>
              actor ! Offer(processes.head, (c: Cause) => c match {
                case End => emitAll(processes.tail) onHalt next
                case cause => Util.Try(next(cause))
              } )
          })
        })

      // fails the signal and queue with given reason
      def fail(cause: Cause): Unit = {
        state = Either3.left3(cause)
        (for {
          _ <- q.failWithCause(cause)
          _ <- done.failWithCause(cause)
        } yield ()).runAsync(_ => ())
      }

      // initially sets signal and starts the source evaluation
      def start: Task[Unit] =
        done.set(false).map { _ => state = nextStep(source) }


      actor = Actor[M]({m =>
        Util.debug(s"~~~ NJN m: $m | open: $opened | state: $state")
        m match {
          // next merged process is available
          // run it with chance to interrupt
          // and give chance to start next process if not bounded
          case Offer(p, next) =>
            opened = opened + 1
            if (maxOpen <= 0 || opened < maxOpen) state = nextStep(Util.Try(next(End)))
            else state = Either3.right3(next)

            //runs the process with a chance to interrupt it using signal `done`
            //interrupt is done via setting the done to `true`
            done.discrete.wye(p)(wye.interrupt)
            .to(q.enqueue)
            .run.runAsync { res =>
              actor ! Finished(res)
            }

          //finished the `upstream` normally but still have some open processes to merging
          case FinishedSource(End) if opened > 0 =>
            state = Either3.left3(End)

          // finished upstream and no processes are running, terminate downstream
          case FinishedSource(rsn) =>
            fail(rsn)

          //merged process terminated. This always != End, Continue due to `Process.runFoldMap`
          //as such terminate the join with given exception
          case Finished(-\/(rsn)) =>
            opened = opened - 1
            fail(state match {
              case Left3(rsn0) => rsn0
              case Middle3(interrupt) => interrupt(Kill); Error(rsn)
              case Right3(next) => Util.Try(next(Kill)).run.runAsync(_ => ()); Error(rsn)
            })

          // One of the processes terminated w/o failure
          // Finish merge if the opened == 0 and upstream terminated
          // or give an opportunity to start next merged process if the maxOpen was hit
          case Finished(\/-(_)) =>
            opened = opened - 1
            state = state match {
              case Right3(next)
                if maxOpen <= 0 || opened < maxOpen => nextStep(Util.Try(next(End)))
              case Left3(End) if opened == 0 => fail(End) ; state
              case Left3(rsn) if opened == 0 => state
              case other                     => other
            }

          // `Downstream` of the merge terminated
          // Kill all upstream processes and eventually the source.
          case FinishedDown(cb) =>
            fail(state match {
              case Left3(_)           => Kill
              case Middle3(interrupt) => interrupt(Kill); Kill
              case Right3(next)       => Kill
            })
            S(cb(\/-(())))

        }})


      (eval_(start) fby q.dequeue)
      .onComplete(eval_(Task.async[Unit] { cb => actor ! FinishedDown(cb) }))
    }

  }

}
