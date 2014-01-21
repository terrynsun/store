package com.treode.async.io

import java.nio.channels._
import java.net.SocketAddress
import java.util.concurrent.Executor

import com.treode.async.Callback

/** Something that can be mocked in tests. */
class ServerSocket (socket: AsynchronousServerSocketChannel, exec: Executor) {

  /** Adapts Callback to Java's NIO CompletionHandler. */
  object SocketHandler extends CompletionHandler [AsynchronousSocketChannel, Callback [Socket]] {
    def completed (v: AsynchronousSocketChannel, cb: Callback [Socket]) = cb (new Socket (v, exec))
    def failed (t: Throwable, cb: Callback [Socket]) = cb.fail (t)
  }

  def bind (addr: SocketAddress): Unit =
    socket.bind (addr)

  def accept (cb: Callback [Socket]): Unit =
    try {
      socket.accept (cb, SocketHandler)
    } catch {
      case t: Throwable => cb.fail (t)
    }

  def close(): Unit =
    socket.close()
}

object ServerSocket {

  def open (group: AsynchronousChannelGroup, exec: Executor): ServerSocket =
    new ServerSocket (AsynchronousServerSocketChannel.open (group), exec)
}