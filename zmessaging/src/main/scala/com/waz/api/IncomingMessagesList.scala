/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.api

object IncomingMessagesList {

  trait MessageListener {
    /**
     * Will be called for each new incoming message.
     */
    def onIncomingMessage(msg: Message): Unit
  }

  trait KnockListener {
    /**
     * Will be called whenever fresh knock event is received on push channel.
     */
    def onKnock(knock: Message): Unit
  }
}

trait IncomingMessagesList extends CoreList[Message] {
  def addKnockListener(listener: IncomingMessagesList.KnockListener): Unit

  def removeKnockListener(listener: IncomingMessagesList.KnockListener): Unit

  def addMessageListener(listener: IncomingMessagesList.MessageListener): Unit

  def removeMessageListener(listener: IncomingMessagesList.MessageListener): Unit
}
