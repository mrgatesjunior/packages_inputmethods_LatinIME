/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <assert.h>
#include <stdio.h>
#include <string>

#define LOG_TAG "LatinIME: proximity_info.cpp"

#include "additional_proximity_chars.h"
#include "defines.h"
#include "dictionary.h"
#include "proximity_info.h"
#include "proximity_info_state.h"

namespace latinime {

inline void copyOrFillZero(void *to, const void *from, size_t size) {
    if (from) {
        memcpy(to, from, size);
    } else {
        memset(to, 0, size);
    }
}

ProximityInfo::ProximityInfo(const std::string localeStr, const int maxProximityCharsSize,
        const int keyboardWidth, const int keyboardHeight, const int gridWidth,
        const int gridHeight, const int mostCommonKeyWidth,
        const int32_t *proximityCharsArray, const int keyCount, const int32_t *keyXCoordinates,
        const int32_t *keyYCoordinates, const int32_t *keyWidths, const int32_t *keyHeights,
        const int32_t *keyCharCodes, const float *sweetSpotCenterXs, const float *sweetSpotCenterYs,
        const float *sweetSpotRadii)
        : MAX_PROXIMITY_CHARS_SIZE(maxProximityCharsSize), KEYBOARD_WIDTH(keyboardWidth),
          KEYBOARD_HEIGHT(keyboardHeight), GRID_WIDTH(gridWidth), GRID_HEIGHT(gridHeight),
          MOST_COMMON_KEY_WIDTH_SQUARE(mostCommonKeyWidth * mostCommonKeyWidth),
          CELL_WIDTH((keyboardWidth + gridWidth - 1) / gridWidth),
          CELL_HEIGHT((keyboardHeight + gridHeight - 1) / gridHeight),
          KEY_COUNT(min(keyCount, MAX_KEY_COUNT_IN_A_KEYBOARD)),
          HAS_TOUCH_POSITION_CORRECTION_DATA(keyCount > 0 && keyXCoordinates && keyYCoordinates
                  && keyWidths && keyHeights && keyCharCodes && sweetSpotCenterXs
                  && sweetSpotCenterYs && sweetSpotRadii),
          mLocaleStr(localeStr) {
    const int proximityGridLength = GRID_WIDTH * GRID_HEIGHT * MAX_PROXIMITY_CHARS_SIZE;
    if (DEBUG_PROXIMITY_INFO) {
        AKLOGI("Create proximity info array %d", proximityGridLength);
    }
    mProximityCharsArray = new int32_t[proximityGridLength];
    memcpy(mProximityCharsArray, proximityCharsArray,
            proximityGridLength * sizeof(mProximityCharsArray[0]));

    copyOrFillZero(mKeyXCoordinates, keyXCoordinates, KEY_COUNT * sizeof(mKeyXCoordinates[0]));
    copyOrFillZero(mKeyYCoordinates, keyYCoordinates, KEY_COUNT * sizeof(mKeyYCoordinates[0]));
    copyOrFillZero(mKeyWidths, keyWidths, KEY_COUNT * sizeof(mKeyWidths[0]));
    copyOrFillZero(mKeyHeights, keyHeights, KEY_COUNT * sizeof(mKeyHeights[0]));
    copyOrFillZero(mKeyCharCodes, keyCharCodes, KEY_COUNT * sizeof(mKeyCharCodes[0]));
    copyOrFillZero(mSweetSpotCenterXs, sweetSpotCenterXs,
            KEY_COUNT * sizeof(mSweetSpotCenterXs[0]));
    copyOrFillZero(mSweetSpotCenterYs, sweetSpotCenterYs,
            KEY_COUNT * sizeof(mSweetSpotCenterYs[0]));
    copyOrFillZero(mSweetSpotRadii, sweetSpotRadii, KEY_COUNT * sizeof(mSweetSpotRadii[0]));

    initializeCodeToKeyIndex();
}

// Build the reversed look up table from the char code to the index in mKeyXCoordinates,
// mKeyYCoordinates, mKeyWidths, mKeyHeights, mKeyCharCodes.
void ProximityInfo::initializeCodeToKeyIndex() {
    memset(mCodeToKeyIndex, -1, (MAX_CHAR_CODE + 1) * sizeof(mCodeToKeyIndex[0]));
    for (int i = 0; i < KEY_COUNT; ++i) {
        const int code = mKeyCharCodes[i];
        if (0 <= code && code <= MAX_CHAR_CODE) {
            mCodeToKeyIndex[code] = i;
        }
    }
}

ProximityInfo::~ProximityInfo() {
    delete[] mProximityCharsArray;
}

inline int ProximityInfo::getStartIndexFromCoordinates(const int x, const int y) const {
    return ((y / CELL_HEIGHT) * GRID_WIDTH + (x / CELL_WIDTH))
            * MAX_PROXIMITY_CHARS_SIZE;
}

bool ProximityInfo::hasSpaceProximity(const int x, const int y) const {
    if (x < 0 || y < 0) {
        if (DEBUG_DICT) {
            AKLOGI("HasSpaceProximity: Illegal coordinates (%d, %d)", x, y);
            assert(false);
        }
        return false;
    }

    const int startIndex = getStartIndexFromCoordinates(x, y);
    if (DEBUG_PROXIMITY_INFO) {
        AKLOGI("hasSpaceProximity: index %d, %d, %d", startIndex, x, y);
    }
    int32_t* proximityCharsArray = mProximityCharsArray;
    for (int i = 0; i < MAX_PROXIMITY_CHARS_SIZE; ++i) {
        if (DEBUG_PROXIMITY_INFO) {
            AKLOGI("Index: %d", mProximityCharsArray[startIndex + i]);
        }
        if (proximityCharsArray[startIndex + i] == KEYCODE_SPACE) {
            return true;
        }
    }
    return false;
}

int ProximityInfo::squaredDistanceToEdge(const int keyId, const int x, const int y) const {
    if (keyId < 0) return true; // NOT_A_ID is -1, but return whenever < 0 just in case
    const int left = mKeyXCoordinates[keyId];
    const int top = mKeyYCoordinates[keyId];
    const int right = left + mKeyWidths[keyId];
    const int bottom = top + mKeyHeights[keyId];
    const int edgeX = x < left ? left : (x > right ? right : x);
    const int edgeY = y < top ? top : (y > bottom ? bottom : y);
    const int dx = x - edgeX;
    const int dy = y - edgeY;
    return dx * dx + dy * dy;
}

void ProximityInfo::calculateNearbyKeyCodes(
        const int x, const int y, const int32_t primaryKey, int *inputCodes) const {
    int32_t *proximityCharsArray = mProximityCharsArray;
    int insertPos = 0;
    inputCodes[insertPos++] = primaryKey;
    const int startIndex = getStartIndexFromCoordinates(x, y);
    if (startIndex >= 0) {
        for (int i = 0; i < MAX_PROXIMITY_CHARS_SIZE; ++i) {
            const int32_t c = proximityCharsArray[startIndex + i];
            if (c < KEYCODE_SPACE || c == primaryKey) {
                continue;
            }
            const int keyIndex = getKeyIndex(c);
            const bool onKey = isOnKey(keyIndex, x, y);
            const int distance = squaredDistanceToEdge(keyIndex, x, y);
            if (onKey || distance < MOST_COMMON_KEY_WIDTH_SQUARE) {
                inputCodes[insertPos++] = c;
                if (insertPos >= MAX_PROXIMITY_CHARS_SIZE) {
                    if (DEBUG_DICT) {
                        assert(false);
                    }
                    return;
                }
            }
        }
        const int additionalProximitySize =
                AdditionalProximityChars::getAdditionalCharsSize(&mLocaleStr, primaryKey);
        if (additionalProximitySize > 0) {
            inputCodes[insertPos++] = ADDITIONAL_PROXIMITY_CHAR_DELIMITER_CODE;
            if (insertPos >= MAX_PROXIMITY_CHARS_SIZE) {
                if (DEBUG_DICT) {
                    assert(false);
                }
                return;
            }

            const int32_t* additionalProximityChars =
                    AdditionalProximityChars::getAdditionalChars(&mLocaleStr, primaryKey);
            for (int j = 0; j < additionalProximitySize; ++j) {
                const int32_t ac = additionalProximityChars[j];
                int k = 0;
                for (; k < insertPos; ++k) {
                    if ((int)ac == inputCodes[k]) {
                        break;
                    }
                }
                if (k < insertPos) {
                    continue;
                }
                inputCodes[insertPos++] = ac;
                if (insertPos >= MAX_PROXIMITY_CHARS_SIZE) {
                    if (DEBUG_DICT) {
                        assert(false);
                    }
                    return;
                }
            }
        }
    }
    // Add a delimiter for the proximity characters
    for (int i = insertPos; i < MAX_PROXIMITY_CHARS_SIZE; ++i) {
        inputCodes[i] = NOT_A_CODE;
    }
}

int ProximityInfo::getKeyIndex(const int c) const {
    if (KEY_COUNT == 0) {
        // We do not have the coordinate data
        return NOT_AN_INDEX;
    }
    const unsigned short baseLowerC = toBaseLowerCase(c);
    if (baseLowerC > MAX_CHAR_CODE) {
        return NOT_AN_INDEX;
    }
    return mCodeToKeyIndex[baseLowerC];
}
} // namespace latinime