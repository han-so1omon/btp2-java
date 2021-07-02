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

package com.iconloop.btp.nativecoin;

import score.Address;

import java.math.BigInteger;

public interface NCSEvents {

    /**
     * (EventLog)
     *
     * @param _from
     * @param _to
     * @param sn
     * @param _assets
     */
    void TransferStart(Address _from, String _to, BigInteger sn, byte[] _assets);

    /**
     * (EventLog)
     *
     * @param _from
     * @param sn
     * @param _code
     * @param _response
     */
    void TransferEnd(Address _from, BigInteger sn, long _code, String _response);

}
