/*
 * Copyright 2021 ICON Foundation
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

package com.iconloop.btp.bmc;

import com.iconloop.btp.lib.BTPAddress;
import score.ByteArrayObjectWriter;
import score.Context;
import score.ObjectReader;
import score.ObjectWriter;

public class LinkMessage {
    private BTPAddress link;

    public BTPAddress getLink() {
        return link;
    }

    public void setLink(BTPAddress link) {
        this.link = link;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("LinkMessage{");
        sb.append("link=").append(link);
        sb.append('}');
        return sb.toString();
    }

    public static void writeObject(ObjectWriter writer, LinkMessage obj) {
        obj.writeObject(writer);
    }

    public static LinkMessage readObject(ObjectReader reader) {
        LinkMessage obj = new LinkMessage();
        reader.beginList();
        obj.setLink(reader.readNullable(BTPAddress.class));
        reader.end();
        return obj;
    }

    public void writeObject(ObjectWriter writer) {
        writer.beginList(1);
        writer.writeNullable(this.getLink());
        writer.end();
    }

    public static LinkMessage fromBytes(byte[] bytes) {
        ObjectReader reader = Context.newByteArrayObjectReader("RLPn", bytes);
        return LinkMessage.readObject(reader);
    }

    public byte[] toBytes() {
        ByteArrayObjectWriter writer = Context.newByteArrayObjectWriter("RLPn");
        LinkMessage.writeObject(writer, this);
        return writer.toByteArray();
    }
}
