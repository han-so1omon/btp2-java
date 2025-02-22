/*
 * Copyright 2023 ICON Foundation
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

package foundation.icon.btp.bmv.eth2;

import foundation.icon.score.util.StringUtil;
import score.Context;
import score.ObjectReader;
import scorex.util.ArrayList;

import java.math.BigInteger;

public class Receipt {
    private byte[] postStatusOrState;
    private BigInteger cumulativeGasUsed;
    private byte[] bloom;
    private Log[] logs;

    public Receipt(byte[] postStatusOrState, BigInteger cumulativeGasUsed, byte[] bloom, Log[] logs) {
        this.postStatusOrState = postStatusOrState;
        this.cumulativeGasUsed = cumulativeGasUsed;
        this.bloom = bloom;
        this.logs = logs;
    }

    Log[] getLogs() {
        return logs;
    }

    public static Receipt readObject(ObjectReader r) {
        r.beginList();
        var postStatusOrState = r.readByteArray();
        var cumulativeGasUsed = r.readBigInteger();
        var bloom = r.readByteArray();
        var logList = new ArrayList<Log>();
        r.beginList();
        while(r.hasNext())
            logList.add(r.read(Log.class));
        r.end();
        var logsLength = logList.size();
        var logs = new Log[logsLength];
        for (int i = 0; i < logsLength; i++)
            logs[i] = logList.get(i);
        r.end();
        return new Receipt(postStatusOrState, cumulativeGasUsed, bloom, logs);
    }

    static Receipt fromBytes(byte[] bytes) {
        ObjectReader reader = Context.newByteArrayObjectReader("RLPn", bytes);
        return Receipt.readObject(reader);
    }

    @Override
    public String toString() {
        return "Receipt{" +
                "postStatusOrState=" + StringUtil.toString(postStatusOrState) +
                ", cumulativeGasUsed=" + cumulativeGasUsed +
                ", bloom=" + StringUtil.toString(bloom) +
                ", logs=" + StringUtil.toString(logs) +
                '}';
    }
}
