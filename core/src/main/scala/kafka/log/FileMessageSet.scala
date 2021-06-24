/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.log

import java.io._
import java.nio._
import java.nio.channels._
import java.util.concurrent.atomic._

import kafka.utils._
import kafka.message._
import kafka.common.KafkaException
import java.util.concurrent.TimeUnit
import kafka.metrics.{KafkaTimer, KafkaMetricsGroup}
import org.apache.kafka.common.errors.CorruptRecordException
import org.apache.kafka.common.network.TransportLayer
import org.apache.kafka.common.utils.Utils

import scala.collection.mutable.ArrayBuffer

/**
 * An on-disk message set. An optional start and end position can be applied to the message set
 * which will allow slicing a subset of the file.
 * @param file The file name for the underlying log data
 * @param channel the underlying file channel used
 * @param start A lower bound on the absolute position in the file from which the message set begins
 * @param end The upper bound on the absolute position in the file at which the message set ends
 * @param isSlice Should the start and end parameters be used for slicing?
 */
@nonthreadsafe
class FileMessageSet private[kafka](@volatile
                                    //对应磁盘上的一个文件
                                    var file: File,
                                    //用于读写文件的channel
                                    private[log] val channel: FileChannel,
                                    //日志文件切片的开始点
                                    private[log] val start: Int,
                                    //日志文件切片的结束点
                                    private[log] val end: Int,
                                    //是否为日志文件的切片
                                    isSlice: Boolean) extends MessageSet with Logging {

  /* the size of the message set in bytes */
  //日志文件的大小
  private val _size =
    if(isSlice)
      new AtomicInteger(end - start) // don't check the file size if this is just a slice view
    else
      new AtomicInteger(math.min(channel.size.toInt, end) - start)

  /* if this is not a slice, update the file pointer to the end of the file */
  if (!isSlice)
    /* set the file position to the last byte in the file */
    channel.position(math.min(channel.size.toInt, end))

  /**
   * Create a file message set with no slicing.
   */
  def this(file: File, channel: FileChannel) =
    this(file, channel, start = 0, end = Int.MaxValue, isSlice = false)

  /**
   * Create a file message set with no slicing
   */
  def this(file: File) =
    this(file, FileMessageSet.openChannel(file, mutable = true))

  /**
   * Create a file message set with no slicing, and with initFileSize and preallocate.
   * For windows NTFS and some old LINUX file system, set preallocate to true and initFileSize
   * with one value (for example 512 * 1024 *1024 ) can improve the kafka produce performance.
   * If it's new file and preallocate is true, end will be set to 0.  Otherwise set to Int.MaxValue.
   */
  def this(file: File, fileAlreadyExists: Boolean, initFileSize: Int, preallocate: Boolean) =
      this(file,
        //创建FileChannel
        channel = FileMessageSet.openChannel(file, mutable = true, fileAlreadyExists, initFileSize, preallocate),
        //start 默认为0
        start = 0,
        //end 默认为Int.MaxValue
        end = ( if ( !fileAlreadyExists && preallocate ) 0 else Int.MaxValue),
        //isSlice 默认false
        isSlice = false)

  /**
   * Create a file message set with mutable option
   */
  def this(file: File, mutable: Boolean) = this(file, FileMessageSet.openChannel(file, mutable))

  /**
   * Create a slice view of the file message set that begins and ends at the given byte offsets
   */
  def this(file: File, channel: FileChannel, start: Int, end: Int) =
    this(file, channel, start, end, isSlice = true)

  /**
   * Return a message set which is a view into this set starting from the given position and with the given size limit.
   *
   * If the size is beyond the end of the file, the end will be based on the size of the file at the time of the read.
   *
   * If this message set is already sliced, the position will be taken relative to that slicing.
   *
   * @param position The start position to begin the read from
   * @param size The number of bytes after the start position to include
   *
   * @return A sliced wrapper on this message set limited based on the given position and size
   */
    //从指定的物理位置 指定读取大小 读取数据
  def read(position: Int, size: Int): FileMessageSet = {
    if(position < 0)
      throw new IllegalArgumentException("Invalid position: " + position)
    if(size < 0)
      throw new IllegalArgumentException("Invalid size: " + size)
    new FileMessageSet(file,
                       channel,
                       start = this.start + position,
                       end = math.min(this.start + position + size, sizeInBytes()))
  }

  /**
   * Search forward for the file position of the last offset that is greater than or equal to the target offset
   * and return its physical position. If no such offsets are found, return null.
   * @param targetOffset The offset to search for.
   * @param startingPosition The starting position in the file to begin searching from.
   */
    //从指定的offset开始 遍历消息集 找到第一个大于等于该offset的消息
    //该方法并不会解压消息
  def searchFor(targetOffset: Long, startingPosition: Int): OffsetPosition = {
    var position = startingPosition
    //12byte length offset+size
    val buffer = ByteBuffer.allocate(MessageSet.LogOverhead)
    val size = sizeInBytes()
    while(position + MessageSet.LogOverhead < size) {
      buffer.rewind()
      channel.read(buffer, position)
      //连12个字节都读取不到 直接抛异常
      if(buffer.hasRemaining)
        throw new IllegalStateException("Failed to read complete buffer for targetOffset %d startPosition %d in %s"
                                        .format(targetOffset, startingPosition, file.getAbsolutePath))
      buffer.rewind()
      //消息集中的offset
      val offset = buffer.getLong()
      if(offset >= targetOffset) // 找到了
        //position 文件中的物理位置
        return OffsetPosition(offset, position)
      val messageSize = buffer.getInt()
      if(messageSize < Message.MinMessageOverhead)
        throw new IllegalStateException("Invalid message size: " + messageSize)
      //略过消息内容 进行下一次读取
      position += MessageSet.LogOverhead + messageSize
    }
      //没找到
    null
  }

  /**
   * Write some of this set to the given channel.
   * @param destChannel The channel to write to.
   * @param writePosition The position in the message set to begin writing from.
   * @param size The maximum number of bytes to write
   * @return The number of bytes actually written.
   */
    //将此消息集中的消息写入指定的channel中
  def writeTo(destChannel: GatheringByteChannel, writePosition: Long, size: Int): Int = {
    // Ensure that the underlying size has not changed.
    val newSize = math.min(channel.size.toInt, end) - start
    if (newSize < _size.get()) {
      throw new KafkaException("Size of FileMessageSet %s has been truncated during write: old size %d, new size %d"
        .format(file.getAbsolutePath, _size.get(), newSize))
    }
    val position = start + writePosition
    val count = math.min(size, sizeInBytes)
    val bytesTransferred = (destChannel match {
      case tl: TransportLayer => tl.transferFrom(channel, position, count)
      case dc => channel.transferTo(position, count, dc)
    }).toInt
    trace("FileMessageSet " + file.getAbsolutePath + " : bytes transferred : " + bytesTransferred
      + " bytes requested for transfer : " + math.min(size, sizeInBytes))
    bytesTransferred
  }

  /**
    * This method is called before we write messages to the socket using zero-copy transfer. We need to
    * make sure all the messages in the message set have the expected magic value.
    *
    * @param expectedMagicValue the magic value expected
    * @return true if all messages have expected magic value, false otherwise
    */
  override def isMagicValueInAllWrapperMessages(expectedMagicValue: Byte): Boolean = {
    var location = start
    val offsetAndSizeBuffer = ByteBuffer.allocate(MessageSet.LogOverhead)
    val crcAndMagicByteBuffer = ByteBuffer.allocate(Message.CrcLength + Message.MagicLength)
    while (location < end) {
      offsetAndSizeBuffer.rewind()
      channel.read(offsetAndSizeBuffer, location)
      if (offsetAndSizeBuffer.hasRemaining)
        return true
      offsetAndSizeBuffer.rewind()
      offsetAndSizeBuffer.getLong // skip offset field
      val messageSize = offsetAndSizeBuffer.getInt
      if (messageSize < Message.MinMessageOverhead)
        throw new IllegalStateException("Invalid message size: " + messageSize)
      crcAndMagicByteBuffer.rewind()
      channel.read(crcAndMagicByteBuffer, location + MessageSet.LogOverhead)
      if (crcAndMagicByteBuffer.get(Message.MagicOffset) != expectedMagicValue)
        return false
      location += (MessageSet.LogOverhead + messageSize)
    }
    true
  }

  /**
   * Convert this message set to use the specified message format.
   */
  def toMessageFormat(toMagicValue: Byte): MessageSet = {
    val offsets = new ArrayBuffer[Long]
    val newMessages = new ArrayBuffer[Message]
    this.foreach { messageAndOffset =>
      val message = messageAndOffset.message
      if (message.compressionCodec == NoCompressionCodec) {
        newMessages += message.toFormatVersion(toMagicValue)
        offsets += messageAndOffset.offset
      } else {
        // File message set only has shallow iterator. We need to do deep iteration here if needed.
        val deepIter = ByteBufferMessageSet.deepIterator(messageAndOffset)
        for (innerMessageAndOffset <- deepIter) {
          newMessages += innerMessageAndOffset.message.toFormatVersion(toMagicValue)
          offsets += innerMessageAndOffset.offset
        }
      }
    }

    if (sizeInBytes > 0 && newMessages.size == 0) {
      // This indicates that the message is too large. We just return all the bytes in the file message set.
      this
    } else {
      // We use the offset seq to assign offsets so the offset of the messages does not change.
      new ByteBufferMessageSet(
        compressionCodec = this.headOption.map(_.message.compressionCodec).getOrElse(NoCompressionCodec),
        offsetSeq = offsets,
        newMessages: _*)
    }
  }

  /**
   * Get a shallow iterator over the messages in the set.
   */
  override def iterator: Iterator[MessageAndOffset] = iterator(Int.MaxValue)

  /**
   * Get an iterator over the messages in the set. We only do shallow iteration here.
   * @param maxMessageSize A limit on allowable message size to avoid allocating unbounded memory.
   * If we encounter a message larger than this we throw an InvalidMessageException.
   * @return The iterator.
   */
    //浅层迭代消息集中的消息
    //MessageAndOffset = offset + message
  def iterator(maxMessageSize: Int): Iterator[MessageAndOffset] = {
    new IteratorTemplate[MessageAndOffset] {
      var location = start
      val sizeOffsetLength = 12
      val sizeOffsetBuffer = ByteBuffer.allocate(sizeOffsetLength)

      override def makeNext(): MessageAndOffset = {
        if(location + sizeOffsetLength >= end)
          return allDone()

        // read the size of the item
        sizeOffsetBuffer.rewind()
        channel.read(sizeOffsetBuffer, location)
        if(sizeOffsetBuffer.hasRemaining)
          return allDone()

        sizeOffsetBuffer.rewind()
        val offset = sizeOffsetBuffer.getLong()
        val size = sizeOffsetBuffer.getInt()
        if(size < Message.MinMessageOverhead || location + sizeOffsetLength + size > end)
          return allDone()
        if(size > maxMessageSize)
          throw new CorruptRecordException("Message size exceeds the largest allowable message size (%d).".format(maxMessageSize))

        // read the item itself
        val buffer = ByteBuffer.allocate(size)
        channel.read(buffer, location + sizeOffsetLength)
        if(buffer.hasRemaining)
          return allDone()
        buffer.rewind()

        // increment the location and return the item
        location += size + sizeOffsetLength
        new MessageAndOffset(new Message(buffer), offset)
      }
    }
  }

  /**
   * The number of bytes taken up by this file set
   */
    //该消息集中已写入的字节数字
  def sizeInBytes(): Int = _size.get()

  /**
   * Append these messages to the message set
   */
   //向消息集中追加消息 必须使用 ByteBufferMessageSet来追加
  def append(messages: ByteBufferMessageSet) {
     //追加消息 返回写入的字节数
    val written = messages.writeFullyTo(channel)
     //修改大小
    _size.getAndAdd(written)
  }

  /**
   * Commit all written data to the physical disk
   */
  def flush() = {
    channel.force(true)
  }

  /**
   * Close this message set
   */
  def close() {
    flush()
    trim()
    channel.close()
  }

  /**
   * Trim file when close or roll to next file
   */
  def trim() {
    truncateTo(sizeInBytes())
  }

  /**
   * Delete this message set from the filesystem
   * @return True iff this message set was deleted.
   */
    //删除消息集 删除日志文件
  def delete(): Boolean = {
    CoreUtils.swallow(channel.close())
    file.delete()
  }

  /**
   * Truncate this file message set to the given size in bytes. Note that this API does no checking that the
   * given size falls on a valid message boundary.
   * In some versions of the JDK truncating to the same size as the file message set will cause an
   * update of the files mtime, so truncate is only performed if the targetSize is smaller than the
   * size of the underlying FileChannel.
   * It is expected that no other threads will do writes to the log when this function is called.
   * @param targetSize The size to truncate to. Must be between 0 and sizeInBytes.
   * @return The number of bytes truncated off
   */
    //将日志文件 截断到指定的size
  def truncateTo(targetSize: Int): Int = {
    val originalSize = sizeInBytes
    if(targetSize > originalSize || targetSize < 0)//参数异常
      throw new KafkaException("Attempt to truncate log segment to " + targetSize + " bytes failed, " +
                               " size of this log segment is " + originalSize + " bytes.")
    if (targetSize < channel.size.toInt) {
      //裁剪文件
      channel.truncate(targetSize)
      //移动position
      channel.position(targetSize)
      _size.set(targetSize)
    }
      //返回裁剪掉的字节数
    originalSize - targetSize
  }

  /**
   * Read from the underlying file into the buffer starting at the given position
   */
    //将数据读到ByteBuffer中
  def readInto(buffer: ByteBuffer, relativePosition: Int): ByteBuffer = {
    channel.read(buffer, relativePosition + this.start)
    buffer.flip()
    buffer
  }

  /**
   * Rename the file that backs this message set
   * @throws IOException if rename fails.
   */
  def renameTo(f: File) {
    try Utils.atomicMoveWithFallback(file.toPath, f.toPath)
    finally this.file = f
  }

}

object FileMessageSet
{
  /**
   * Open a channel for the given file
   * For windows NTFS and some old LINUX file system, set preallocate to true and initFileSize
   * with one value (for example 512 * 1025 *1024 ) can improve the kafka produce performance.
   * @param file File path
   * @param mutable mutable
   * @param fileAlreadyExists File already exists or not
   * @param initFileSize The size used for pre allocate file, for example 512 * 1025 *1024
   * @param preallocate Pre allocate file or not, gotten from configuration.
   */
  def openChannel(file: File, mutable: Boolean, fileAlreadyExists: Boolean = false, initFileSize: Int = 0, preallocate: Boolean = false): FileChannel = {
    if (mutable) {//是否可变 换句话说就是 是否是可写还是只读
      if (fileAlreadyExists)//已经存在该文件
        new RandomAccessFile(file, "rw").getChannel()
      else {
        if (preallocate) {//预分配
          val randomAccessFile = new RandomAccessFile(file, "rw")
          randomAccessFile.setLength(initFileSize)
          randomAccessFile.getChannel()
        }
        else
          //创建可读写的
          new RandomAccessFile(file, "rw").getChannel()
      }
    }
    else
      //创建只读的
      new FileInputStream(file).getChannel()
  }
}

object LogFlushStats extends KafkaMetricsGroup {
  val logFlushTimer = new KafkaTimer(newTimer("LogFlushRateAndTimeMs", TimeUnit.MILLISECONDS, TimeUnit.SECONDS))
}
