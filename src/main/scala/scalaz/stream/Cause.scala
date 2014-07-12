package scalaz.stream


/**
 * Defines termination cause for the process.
 * Cause is always wrapped in `Halt` and controls process flow.
 */
sealed trait Cause {
  /**
   * Produces a cause that was caused by `cause`
   * @param cause
   * @return
   */
  def causedBy(cause: Cause): Cause = {
    (this, cause) match {
      case (End, End)                 => End
      case (End, Kill)                => Kill
      case (Kill, End | Kill)         => Kill
      case (End | Kill, err@Error(_)) => err
      case (err@Error(_), End | Kill) => err
      case (Error(rsn1), Error(rsn2)) => Error(CausedBy(rsn1, rsn2))
    }
  }

  /**
   * Converts cause to `Kill` or on `Error`
   * @return
   */
  def kill: Cause = {
    this match {
      case End => Kill
      case other => other
    }
  }
}

/**
 * Process terminated normally due to End of input.
 * That means items from Emit ha been exhausted.
 */
case object End extends Cause



/**
 * Signals force-full process termination.
 * Process can be killed when merged (pipe,tee,wye,njoin) and other merging stream or
 * resulting downstream requested termination of process.
 * This shall cause process to run all cleanup actions and then terminate normally
 */
case object Kill extends Cause

/**
 * Signals, that evaluation of last await resulted in error.
 *
 * If error is not handled, this will cause the process to terminate with supplier error.
 *
 * @param rsn Error thrown by last await.
 *
 */
case class Error(rsn: Throwable) extends Cause


/**
 * Wrapper for Exception that was caused by other Exception during the
 * Execution of the Process
 */
case class CausedBy(e: Throwable, cause: Throwable) extends Exception(cause) {
  override def toString = s"$e caused by: $cause"
  override def getMessage: String = toString
}

/**
 * wrapper to signal cause for termination.
 * This is usefull when cause needs to be propagated out of process domain (i.e. Task)
 */
case class Terminated(cause:Cause) extends Exception