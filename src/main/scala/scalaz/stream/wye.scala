package scalaz.stream

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scalaz.\/._
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.stream.Process._
import scalaz.stream.ReceiveY._
import scalaz.stream.Util._
import scalaz.stream.process1.AwaitP1
import scalaz.{-\/, Either3, Left3, Middle3, Right3, \/, \/-}


object wye {

  /**
   * A `Wye` which emits values from its right branch, but allows up to `n`
   * elements from the left branch to enqueue unanswered before blocking
   * on the right branch.
   */
  def boundedQueue[I](n: Int): Wye[Any,I,I] =
    yipWithL(n)((i,i2) => i2)

  /**
   * After each input, dynamically determine whether to read from the left, right, or both,
   * for the subsequent input, using the provided functions `f` and `g`. The returned
   * `Wye` begins by reading from the left side and is left-biased--if a read of both branches
   * returns a `These(x,y)`, it uses the signal generated by `f` for its next step.
   */
  def dynamic[I,I2](f: I => wye.Request, g: I2 => wye.Request): Wye[I,I2,ReceiveY[I,I2]] = {
    import scalaz.stream.wye.Request._
    def go(signal: wye.Request): Wye[I,I2,ReceiveY[I,I2]] = signal match {
      case L => awaitL[I].flatMap { i => emit(ReceiveL(i)) fby go(f(i)) }
      case R => awaitR[I2].flatMap { i2 => emit(ReceiveR(i2)) fby go(g(i2)) }
      case Both => awaitBoth[I,I2].flatMap {
        case t@ReceiveL(i) => emit(t) fby go(f(i))
        case t@ReceiveR(i2) => emit(t) fby go(g(i2))
        case HaltOne(rsn) => Halt(rsn)
      }
    }
    go(L)
  }

  /**
   * A `Wye` which echoes the right branch while draining the left,
   * taking care to make sure that the left branch is never more
   * than `maxUnacknowledged` behind the right. For example:
   * `src.connect(snk)(observe(10))` will output the the same thing
   * as `src`, but will as a side effect direct output to `snk`,
   * blocking on `snk` if more than 10 elements have enqueued
   * without a response.
   */
  def drainL[I](maxUnacknowledged: Int): Wye[Any,I,I] =
    wye.flip(drainR(maxUnacknowledged))

  /**
   * A `Wye` which echoes the left branch while draining the right,
   * taking care to make sure that the right branch is never more
   * than `maxUnacknowledged` behind the left. For example:
   * `src.connect(snk)(observe(10))` will output the the same thing
   * as `src`, but will as a side effect direct output to `snk`,
   * blocking on `snk` if more than 10 elements have enqueued
   * without a response.
   */
  def drainR[I](maxUnacknowledged: Int): Wye[I,Any,I] =
    yipWithL[I,Any,I](maxUnacknowledged)((i,i2) => i)

  /**
   * Invokes `dynamic` with `I == I2`, and produces a single `I` output. Output is
   * left-biased: if a `These(i1,i2)` is emitted, this is translated to an
   * `emitSeq(List(i1,i2))`.
   */
  def dynamic1[I](f: I => wye.Request): Wye[I,I,I] =
    dynamic(f, f).flatMap {
      case ReceiveL(i) => emit(i)
      case ReceiveR(i) => emit(i)
      case HaltOne(rsn) => Halt(rsn)
    }

  /**
   * Nondeterminstic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   */
  def either[I,I2]: Wye[I,I2,I \/ I2] = {
    def go: Wye[I,I2,I \/ I2] =
      receiveBoth[I,I2,I \/ I2]({
        case ReceiveL(i) => emit(left(i)) fby go
        case ReceiveR(i) => emit(right(i)) fby go
        case HaltL(End)     => awaitR[I2].map(right).repeat
        case HaltR(End)     => awaitL[I].map(left).repeat
        case h@HaltOne(rsn) => Halt(rsn)
      })
    go
  }

  /**
   * Continuous wye, that first reads from Left to get `A`,
   * Then when `A` is not available it reads from R echoing any `A` that was received from Left
   * Will halt once any of the sides halt
   */
  def echoLeft[A]: Wye[A, Any, A] = {
    def go(a: A): Wye[A, Any, A] =
      receiveBoth({
        case ReceiveL(l)  => emit(l) fby go(l)
        case ReceiveR(_)  => emit(a) fby go(a)
        case HaltOne(rsn) => Halt(rsn)
      })
    awaitL[A].flatMap(s => emit(s) fby go(s))
  }

  /**
   * Let through the right branch as long as the left branch is `false`,
   * listening asynchronously for the left branch to become `true`.
   * This halts as soon as the right or left branch halts.
   */
  def interrupt[I]: Wye[Boolean, I, I] = {
    def go[I]: Wye[Boolean, I, I] =
      awaitBoth[Boolean, I].flatMap {
        case ReceiveR(i)    => emit(i) ++ go
        case ReceiveL(kill) => if (kill) halt else go
        case HaltOne(e)     => Halt(e)
      }
    go
  }


  /**
   * Non-deterministic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   *
   * Will terminate once both sides terminate.
   */
  def merge[I]: Wye[I,I,I] = {
    def go: Wye[I,I,I] =
      receiveBoth[I,I,I]({
        case ReceiveL(i) => emit(i) fby go
        case ReceiveR(i) => emit(i) fby go
        case HaltL(End)   => awaitR.repeat
        case HaltR(End)   => awaitL.repeat
        case HaltOne(rsn) => Halt(rsn)
      })
    go
  }

  /**
   * Like `merge`, but terminates whenever one side terminate.
   */
  def mergeHaltBoth[I]: Wye[I,I,I] = {
    def go: Wye[I,I,I] =
      receiveBoth[I,I,I]({
        case ReceiveL(i) => emit(i) fby go
        case ReceiveR(i) => emit(i) fby go
        case HaltOne(rsn) => Halt(rsn)
      })
    go
  }

  /**
   * Like `merge`, but terminates whenever left side terminates.
   * use `flip` to reverse this for the right side
   */
  def mergeHaltL[I]: Wye[I,I,I] = {
    def go: Wye[I,I,I] =
      receiveBoth[I,I,I]({
        case ReceiveL(i) => emit(i) fby go
        case ReceiveR(i) => emit(i) fby go
        case HaltR(End)   => awaitL.repeat
        case HaltOne(rsn) => Halt(rsn)
      })
    go
  }

  /**
   * Like `merge`, but terminates whenever right side terminates
   */
  def mergeHaltR[I]: Wye[I,I,I] =
    wye.flip(mergeHaltL)

  /**
   * A `Wye` which blocks on the right side when either
   *   a) the age of the oldest unanswered element from the left size exceeds the given duration, or
   *   b) the number of unanswered elements from the left exceeds `maxSize`.
   */
  def timedQueue[I](d: Duration, maxSize: Int = Int.MaxValue): Wye[Duration,I,I] = {
    def go(q: Vector[Duration]): Wye[Duration,I,I] =
      awaitBoth[Duration,I].flatMap {
        case ReceiveL(d2) =>
          if (q.size >= maxSize || (d2 - q.headOption.getOrElse(d2) > d))
            awaitR[I].flatMap(i => emit(i) fby go(q.drop(1)))
          else
            go(q :+ d2)
        case ReceiveR(i) => emit(i) fby (go(q.drop(1)))
        case HaltOne(rsn) => Halt(rsn)
      }
    go(Vector())
  }


  /**
   * `Wye` which repeatedly awaits both branches, emitting any values
   * received from the right. Useful in conjunction with `connect`,
   * for instance `src.connect(snk)(unboundedQueue)`
   */
  def unboundedQueue[I]: Wye[Any,I,I] =
    awaitBoth[Any,I].flatMap {
      case ReceiveL(_) => halt
      case ReceiveR(i) => emit(i) fby unboundedQueue
      case HaltOne(rsn) => Halt(rsn)
    }


  /** Nondeterministic version of `zip` which requests both sides in parallel. */
  def yip[I,I2]: Wye[I,I2,(I,I2)] = yipWith((_,_))

  /**
   * Left-biased, buffered version of `yip`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left.
   */
  def yipL[I,I2](n: Int): Wye[I,I2,(I,I2)] =
    yipWithL(n)((_,_))

  /** Nondeterministic version of `zipWith` which requests both sides in parallel. */
  def yipWith[I,I2,O](f: (I,I2) => O): Wye[I,I2,O] =
    awaitBoth[I,I2].flatMap {
      case ReceiveL(i) => awaitR[I2].flatMap(i2 => emit(f(i,i2)) ++ yipWith(f))
      case ReceiveR(i2) => awaitL[I].flatMap(i => emit(f(i,i2)) ++ yipWith(f))
      case HaltOne(rsn) => Halt(rsn)
    }

  /**
   * Left-biased, buffered version of `yipWith`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left.
   */
  def yipWithL[I,O,O2](n: Int)(f: (I,O) => O2): Wye[I,O,O2] = {
    def go(buf: Vector[I]): Wye[I,O,O2] =
      if (buf.size > n) awaitR[O].flatMap { o =>
        emit(f(buf.head,o)) ++ go(buf.tail)
      }
      else if (buf.isEmpty) awaitL[I].flatMap { i => go(buf :+ i) }
      else awaitBoth[I,O].flatMap {
        case ReceiveL(i) => go(buf :+ i)
        case ReceiveR(o) => emit(f(buf.head,o)) ++ go(buf.tail)
        case HaltOne(rsn) => Halt(rsn)
      }
    go(Vector())
  }

  //////////////////////////////////////////////////////////////////////
  // Helper combinator functions, useful when working with wye directly
  //////////////////////////////////////////////////////////////////////

  /**
   * Transform the left input of the given `Wye` using a `Process1`.
   */
  def attachL[I0,I,I2,O](p1: Process1[I0,I])(y: Wye[I,I2,O]): Wye[I0,I2,O] =  {
    y.step match {
      case ys@Step(emt@Emit(os)) =>
        emt onHalt (rsn => attachL(p1)(ys.next(rsn)))

      case Step(AwaitL(rcv)) => p1.step match {
        case s1@Step(Emit(is)) => attachL(Try(s1.continue))(feedL(is)(y))
        case s1@Step(AwaitP1(rcv1)) =>  wye.receiveL(i0 => attachL(p1.feed1(i0))(y))
        case hlt@Halt(rsn) => attachL(hlt)(disconnectL(rsn)(y))
      }

      case ys@Step(AwaitR(rcv)) => wye.receiveR(i2=> attachL(p1)(feed1R(i2)(y)))

      case ys@Step(AwaitBoth(rcv)) => p1.step match {
        case s1@Step(Emit(is)) => attachL(s1.continue)(feedL(is)(y))
        case s1@Step(AwaitP1(rcv1)) =>
            receiveBoth {
              case ReceiveL(i0) => attachL(p1.feed1(i0))(y)
              case ReceiveR(i2) => attachL(p1)(feed1R(i2)(y))
              case HaltL(rsn) =>  attachL(p1)(disconnectL(rsn)(y))
              case HaltR(rsn) =>  attachL(p1)(disconnectR(rsn)(y))
            }
        case hlt@Halt(rsn) => attachL(hlt)(disconnectL(rsn)(y))
      }

      case hlt@Halt(_) => hlt
    }
  }

  /**
   * Transform the right input of the given `Wye` using a `Process1`.
   */
  def attachR[I,I1,I2,O](p: Process1[I1,I2])(w: Wye[I,I2,O]): Wye[I,I1,O] =
    flip(attachL(p)(flip(w)))


  /**
   * Transforms the wye so it will stop to listen on left side.
   * Instead all requests on the left side are converted to termination with `End`,
   * and will terminate once the right side will terminate as well.
   * Transforms `AwaitBoth` to `AwaitR`
   * Transforms `AwaitL` to termination with `End`
   */
  def detach1L[I,I2,O](y: Wye[I,I2,O]): Wye[I,I2,O] =
    disconnectL(End)(y)


  /** right alternative of detach1L **/
  def detach1R[I,I2,O](y: Wye[I,I2,O]): Wye[I,I2,O] =
    disconnectR(End)(y)

  /**
   * Feed a single `ReceiveY` value to a `Wye`.
   */
  def feed1[I,I2,O](r: ReceiveY[I,I2])(w: Wye[I,I2,O]): Wye[I,I2,O] =
    r match {
      case ReceiveL(i) => feed1L(i)(w)
      case ReceiveR(i2) => feed1R(i2)(w)
      case HaltL(e) => disconnectL(e)(w)
      case HaltR(e) => disconnectR(e)(w)
    }

  /** Feed a single value to the left branch of a `Wye`. */
  def feed1L[I,I2,O](i: I)(w: Wye[I,I2,O]): Wye[I,I2,O] =
    feedL(Vector(i))(w)

  /** Feed a single value to the right branch of a `Wye`. */
  def feed1R[I,I2,O](i2: I2)(w: Wye[I,I2,O]): Wye[I,I2,O] =
    feedR(Vector(i2))(w)

  /** Feed a sequence of inputs to the left side of a `Tee`. */
  def feedL[I,I2,O](is: Seq[I])(y: Wye[I,I2,O]): Wye[I,I2,O] = {
    @tailrec
    def go(in: Seq[I], out: Vector[Seq[O]], cur: Wye[I,I2,O]): Wye[I,I2,O] = {
      Util.debug(s"FEEDL src: $is | in: $in | out: $out | cur: $cur ")
      cur.step match {
        case ys@Step(Emit(os)) =>
          go(in, out :+ os, ys.continue)

        case ys@Step(AwaitL(rcv)) =>
          if (in.nonEmpty) go(in.tail, out, Try(rcv(in.head)) onHalt ys.next)
          else emitAll(out.flatten) fby cur

        case ys@Step(AwaitR(rcv)) =>
          emitAll(out.flatten) fby
            wye.receiveR(i2 => feedL(in)(rcv(i2) onHalt ys.next ))

        case s@Step(AwaitBoth(rcv)) =>
          if (in.nonEmpty) go(in.tail, out, Try(rcv(ReceiveY.ReceiveL(in.head))) onHalt s.next)
          else emitAll(out.flatten) fby cur

        case Halt(rsn)                  => emitAll(out.flatten).causedBy(rsn)

      }
    }
    go(is, Vector(), y)
  }

  /** Feed a sequence of inputs to the right side of a `Wye`. */
  def feedR[I,I2,O](i2s: Seq[I2])(y: Wye[I,I2,O]): Wye[I,I2,O] = {
    @tailrec
    def go(in: Seq[I2], out: Vector[Seq[O]], cur: Wye[I,I2,O]): Wye[I,I2,O] = {

      Util.debug(s"FEEDR src: $i2s | in: $in | out: $out | cur: $cur ")
      cur.step match {
        case ys@Step(Emit(os)) =>
          go(in, out :+ os, ys.continue)

        case ys@Step(AwaitR(rcv)) =>
          if (in.nonEmpty) go(in.tail, out, Try(rcv(in.head)) onHalt ys.next)
          else emitAll(out.flatten) fby cur

        case ys@Step(AwaitL(rcv)) => //todo in case the receiveL is Killed or Error we won't feed the onhalt. is this really what we want?
          emitAll(out.flatten) fby
             wye.receiveL(i => feedR(in)(rcv(i) onHalt ys.next ))

        case ys@Step(AwaitBoth(rcv)) =>
          if (in.nonEmpty) go(in.tail, out, Try(rcv(ReceiveY.ReceiveR(in.head))) onHalt ys.next)
          else emitAll(out.flatten) fby cur

        case Halt(rsn)                  => emitAll(out.flatten).causedBy(rsn)

      }
    }
    go(i2s, Vector(), y)
  }

  /**
   * Convert right requests to left requests and vice versa.
   */
  def flip[I,I2,O](y: Wye[I,I2,O]): Wye[I2,I,O] = {
    val ys = y.step
    def next(rsn: Cause) = flip(ys.next(rsn))
    ys match {
      case Step(Emit(os))       => emitAll(os) onHalt next
      case Step(AwaitL(rcv))    => wye.receiveR[I2, I, O](i => flip(rcv(i))) onHalt next
      case Step(AwaitR(rcv))    => wye.receiveL[I2, I, O](i2 => flip(rcv(i2))) onHalt next
      case Step(AwaitBoth(rcv)) => wye.receiveBoth[I2, I, O](ry => flip(rcv(ry.flip))) onHalt next
      case hlt@Halt(rsn)           => hlt
    }
  }

  /**
   * Signals to wye, that Left side terminated.
   * Reason for termination is `rsn`. Any `Left` requests will be terminated with `rsn`
   * any wye will be switched to listen only on Right side.
   */
  def disconnectL[I, I2, O](cause: Cause)(y: Wye[I, I2, O]): Wye[I, I2, O] = {
    val ys = y.step
    def next(rsn:Cause) = disconnectL(cause)(ys.next(rsn))
    debug(s"DISR $ys | rsn $cause")
    ys match {
      case Step(emt@Emit(os)) => emt onHalt next
      case Step(AwaitL(rcv)) => suspend(next(cause))
      case Step(AwaitR(rcv)) => wye.receiveR[I,I2,O](i => disconnectL(cause)(rcv(i))) onHalt next
      case Step(AwaitBoth(rcv)) => wye.receiveBoth[I,I2,O]( yr => disconnectL(cause)(rcv(yr))) onHalt next
      case hlt@Halt(_) => hlt
    }
  }

  /**
   * Right side alternative for `disconnectL`
   */
  def disconnectR[I, I2, O](cause: Cause)(y: Wye[I, I2, O]): Wye[I, I2, O] = {
      val ys = y.step
      def next(rsn:Cause) = disconnectR(cause)(ys.next(rsn))
      debug(s"DISR $ys | rsn $cause")
      ys match {
        case Step(emt@Emit(os)) => emt onHalt next
        case Step(AwaitR(rcv)) => suspend(next(cause))
        case Step(AwaitL(rcv)) => wye.receiveL[I,I2,O](i => disconnectR(cause)(rcv(i))) onHalt next
        case Step(AwaitBoth(rcv)) => wye.receiveBoth[I,I2,O]( yr => disconnectR(cause)(rcv(yr))) onHalt next
        case hlt@Halt(_) => hlt
      }
  }

  ////////////////////////////////////////////////////////////////////////
  // Request Algebra
  ////////////////////////////////////////////////////////////////////////

  /** Indicates required request side **/
  trait Request

  object Request {
    /** Left side **/
    case object L extends Request
    /** Right side **/
    case object R extends Request
    /** Both, or Any side **/
    case object Both extends Request
  }


  //////////////////////////////////////////////////////////////////////
  // De-constructors and type helpers
  //////////////////////////////////////////////////////////////////////

  type WyeAwaitL[I,I2,O] = Await[Env[I,I2]#Y,Env[I,Any]#Is[I],O]
  type WyeAwaitR[I,I2,O] = Await[Env[I,I2]#Y,Env[Any,I2]#T[I2],O]
  type WyeAwaitBoth[I,I2,O] = Await[Env[I,I2]#Y,Env[I,I2]#Y[ReceiveY[I,I2]],O]

  def receiveL[I,I2,O](rcv:I => Wye[I,I2,O]) : Wye[I,I2,O] =
    await(L[I]: Env[I,I2]#Y[I])(rcv)

  def receiveR[I,I2,O](rcv:I2 => Wye[I,I2,O]) : Wye[I,I2,O] =
    await(R[I2]: Env[I,I2]#Y[I2])(rcv)

  def receiveBoth[I,I2,O](rcv:ReceiveY[I,I2] => Wye[I,I2,O]): Wye[I,I2,O] =
    await(Both[I,I2]: Env[I,I2]#Y[ReceiveY[I,I2]])(rcv)


  object AwaitL {

    def unapply[I,I2,O](self: Wye[I,I2,O]):
    Option[(I => Wye[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 0 => Some((i : I) => Try(rcv(right(i)).run))
      case _ => None
    }

    /** Like `AwaitL.unapply` only allows fast test that wye is awaiting on left side **/
    object is {
      def unapply[I,I2,O](self: WyeAwaitL[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 0 => true
        case _ => false
      }
    }
  }


  object AwaitR {
    def unapply[I,I2,O](self: Wye[I,I2,O]):
    Option[(I2 => Wye[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 1 => Some((i2 : I2) => Try(rcv(right(i2)).run))
      case _ => None
    }

    /** Like `AwaitR.unapply` only allows fast test that wye is awaiting on right side **/
    object is {
      def unapply[I,I2,O](self: WyeAwaitR[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 1 => true
        case _ => false
      }
    }
  }
  object AwaitBoth {
    def unapply[I,I2,O](self: Wye[I,I2,O]):
    Option[(ReceiveY[I,I2] => Wye[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 2 => Some((r : ReceiveY[I,I2]) => Try(rcv(right(r)).run))
      case _ => None
    }


    /** Like `AwaitBoth.unapply` only allows fast test that wye is awaiting on both sides **/
    object is {
      def unapply[I,I2,O](self: WyeAwaitBoth[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 2 => true
        case _ => false
      }
    }

  }

  //////////////////////////////////////////////////////////////////
  // Implementation
  //////////////////////////////////////////////////////////////////

  /**
   * Implementation of wye.
   *
   * @param pl    left process
   * @param pr    right process
   * @param y0    wye to control queueing and merging
   * @param S     strategy, preferably executor service based
   * @tparam L    Type of left process element
   * @tparam R    Type of right process elements
   * @tparam O    Output type of resulting process
   * @return      Process with merged elements.
   */
  def apply[L, R, O](pl: Process[Task, L], pr: Process[Task, R])(y0: Wye[L, R, O])(implicit S: Strategy): Process[Task, O] =
    suspend {

      val Left = new Env[L, R].Left
      val Right = new Env[L, R].Right

      sealed trait M
      case class Ready[A](side: Env[L, R]#Y[A], result: Cause \/ (Seq[A], Cause => Process[Task, A])) extends M
      case class Get(cb: (Throwable \/ (Cause \/ Seq[O])) => Unit) extends M
      case class DownDone(cb: (Throwable \/ Unit) => Unit) extends M

      type SideState[A] = Either3[Cause, Cause => Unit, Cause => Process[Task, A]]

      //current state of the wye
      var yy: Wye[L, R, O] = y0

      //cb to be completed for `out` side
      var out: Option[(Cause \/ Seq[O])  => Unit] = None

      //forward referenced actor
      var a: Actor[M] = null

      //Bias for reading from either left or right.
      var leftBias: Boolean = true

      // states of both sides
      var left: SideState[L] = Either3.right3( _ => pl  )
      var right: SideState[R] = Either3.right3( _ => pr )

      // runs evaluation of next Seq[A] from either L/R
      // this signals to actor the next step of either left or right side
      // whenever that side is ready (emited Seq[O] or is done.
      def runSide[A](side: Env[L, R]#Y[A])(state: SideState[A]): SideState[A] = state match {
        case Left3(rsn)         => a ! Ready[A](side, -\/(rsn)); state //just safety callback
        case Middle3(interrupt) => state //no-op already awaiting the result  //todo: don't wee nedd a calback there as well.
        case Right3(next)       => Either3.middle3(Try(next(End)).runAsync { res => a ! Ready[A](side, res) })
      }

      val runSideLeft = runSide(Left) _
      val runSideRight = runSide(Right) _


      // kills the given side either interrupts the execution
      // or creates next step for the process and then runs killed step.
      // note that this function apart from returning the next state perform the side effects
      def kill[A](side: Env[L, R]#Y[A])(state: SideState[A]): SideState[A] = {
        state match {
          case Middle3(interrupt) =>
            interrupt(Kill)
            Either3.middle3((_: Cause) => ()) //rest the interrupt so it won't get interrupted again

          case Right3(next) =>
            Try(next(Kill)).run.runAsync(_ => a ! Ready[A](side, -\/(Kill)))
            Either3.middle3((_: Cause) => ()) // no-op cleanup can't be interrupted

          case left@Left3(_) =>
            left
        }
      }

      def killLeft = kill(Left) _
      def killRight = kill(Right) _

      //checks if given state is done
      def isDone[A](state: SideState[A]) = state.leftOr(false)(_ => true)


      // halts the open request if wye and L/R are done, and returns None
      // otherwise returns cb
      def haltIfDone(
        y: Wye[L, R, O]
        , l: SideState[L]
        , r: SideState[R]
        , cb: Option[(Cause \/ Seq[O]) => Unit]
        ): Option[(Cause \/ Seq[O]) => Unit] = {
        cb match {
          case Some(cb0) =>
            if (isDone(l) && isDone(r)) {
              y.unemit._2 match {
                case Halt(rsn) =>
                  yy = Halt(rsn)
                  S(cb0(-\/(rsn))); None
                case other     => cb
              }
            } else cb
          case None      => None
        }
      }




      // Consumes any output form either side and updates wye with it.
      // note it signals if the other side has to be killed
      def sideReady[A](
        side: Env[L, R]#Y[A])(
        result: Cause \/ (Seq[A], Cause => Process[Task, A])
        ): (SideState[A], (Cause \/ Seq[A])) = {
        result match {
          case -\/(rsn)        => (Either3.left3(rsn), -\/(rsn))
          case \/-((as, next)) => (Either3.right3(next), \/-(as))
        }
      }

      def sideReadyLeft(
        result: Cause \/ (Seq[L], Cause => Process[Task, L])
        , y: Wye[L, R, O]): Wye[L, R, O] = {
        val (state, input) = sideReady(Left)(result)
        left = state
        input.fold(rsn => wye.disconnectL(rsn)(y), ls => wye.feedL(ls)(y))
      }

      def sideReadyRight(
        result: Cause \/ (Seq[R], Cause => Process[Task, R])
        , y: Wye[L, R, O]): Wye[L, R, O] = {
        val (state, input) = sideReady(Right)(result)
        right = state
        input.fold(rsn => wye.disconnectR(rsn)(y), rs => wye.feedR(rs)(y))
      }

      // interprets a single step of wye.
      // if wye is at emit, it tries to complete cb, if cb is nonEmpty
      // if wye is at await runs either side
      // if wye is halt kills either side
      // returns next state of wye and callback
      def runY(y: Wye[L, R, O], cb: Option[(Cause \/ Seq[O]) => Unit])
      : (Wye[L, R, O], Option[(Cause \/ Seq[O]) => Unit]) = {
        @tailrec
        def go(cur: Wye[L, R, O]): (Wye[L, R, O], Option[(Cause \/ Seq[O]) => Unit]) = {
          Util.debug(s"YY cur $cur | cb: $cb | L: $left | R: $right")
          cur.step match {
            case s@Step(Emit(Seq())) =>
              go(s.continue)

            case s@Step(Emit(os)) =>
              cb match {
                case Some(cb0) => S(cb0(\/-(os))); (s.continue, None)
                case None      => (cur, None)
              }

            case Step(AwaitL.is()) =>
              left = runSideLeft(left)
              leftBias = false
              (cur, cb)

            case Step(AwaitR.is()) =>
              right = runSideRight(right)
              leftBias = true
              (cur, cb)

            case Step(AwaitBoth.is()) =>
              if (leftBias) {left = runSideLeft(left); right = runSideRight(right) }
              else {right = runSideRight(right); left = runSideLeft(left) }
              leftBias = !leftBias
              (cur, cb)

            case Halt(_) =>
              if (!isDone(left)) left = killLeft(left)
              if (!isDone(right)) right = killRight(right)
              (cur, cb)

          }
        }
        go(y)
      }



      a = Actor[M]({ m =>
        Util.debug(s"+++ WYE m: $m | yy: $yy | out: $out | l: $left | r: $right")

        m match {
          case Ready(side, result) =>
            val (y, cb) =
              if (side == Left) {
                val resultL = result.asInstanceOf[(Cause \/ (Seq[L], Cause => Process[Task, L]))]
                runY(sideReadyLeft(resultL, yy), out)
              } else {
                val resultR = result.asInstanceOf[(Cause \/ (Seq[R], Cause => Process[Task, R]))]
                runY(sideReadyRight(resultR, yy), out)
              }
            yy = y
            out = haltIfDone(y, left, right, cb)


          case Get(cb0) =>
            val (y, cb) = runY(yy, Some((r:Cause \/ Seq[O]) => cb0(\/-(r))))
            yy = y
            out = haltIfDone(y, left, right, cb)

          case DownDone(cb) =>
            if (!yy.isHalt) yy = halt
            left = killLeft(left)
            right = killRight(right)
            if (isDone(left) && isDone(right)) S(cb(\/-(())))
            else out = Some((r: Cause \/ Seq[O]) => cb(\/-(())))
        }
      })(S)

      repeatEval(Task.async[Cause \/ Seq[O]] { cb => a ! Get(cb) })
      .flatMap(_.fold[Process[Task,O]](Halt.apply,emitAll))
      .onComplete(eval_(Task.async[Unit](cb => a ! DownDone(cb))))
    }
}


protected[stream] trait WyeOps[+O] {
  val self: Process[Task, O]

  /**
   * Like `tee`, but we allow the `Wye` to read non-deterministically
   * from both sides at once.
   *
   * If `y` is in the state of awaiting `Both`, this implementation
   * will continue feeding `y` from either left or right side,
   * until either it halts or _both_ sides halt.
   *
   * If `y` is in the state of awaiting `L`, and the left
   * input has halted, we halt. Likewise for the right side.
   *
   * For as long as `y` permits it, this implementation will _always_
   * feed it any leading `Emit` elements from either side before issuing
   * new `F` requests. More sophisticated chunking and fairness
   * policies do not belong here, but should be built into the `Wye`
   * and/or its inputs.
   *
   * The strategy passed in must be stack-safe, otherwise this implementation
   * will throw SOE. Preferably use one of the `Strategys.Executor(es)` based strategies
   */
  final def wye[O2, O3](p2: Process[Task, O2])(y: Wye[O, O2, O3])(implicit S: Strategy): Process[Task, O3] =
    scalaz.stream.wye[O, O2, O3](self, p2)(y)(S)

  /** Non-deterministic version of `zipWith`. Note this terminates whenever one of streams terminate */
  def yipWith[O2,O3](p2: Process[Task,O2])(f: (O,O2) => O3)(implicit S:Strategy): Process[Task,O3] =
    self.wye(p2)(scalaz.stream.wye.yipWith(f))

  /** Non-deterministic version of `zip`. Note this terminates whenever one of streams terminate */
  def yip[O2](p2: Process[Task,O2])(implicit S:Strategy): Process[Task,(O,O2)] =
    self.wye(p2)(scalaz.stream.wye.yip)

  /** Non-deterministic interleave of both streams.
    * Emits values whenever either is defined. Note this terminates after BOTH sides terminate */
  def merge[O2>:O](p2: Process[Task,O2])(implicit S:Strategy): Process[Task,O2] =
    self.wye(p2)(scalaz.stream.wye.merge)

  /** Non-deterministic interleave of both streams. Emits values whenever either is defined.
    * Note this terminates after BOTH sides terminate  */
  def either[O2>:O,O3](p2: Process[Task,O3])(implicit S:Strategy): Process[Task,O2 \/ O3] =
    self.wye(p2)(scalaz.stream.wye.either)
}
