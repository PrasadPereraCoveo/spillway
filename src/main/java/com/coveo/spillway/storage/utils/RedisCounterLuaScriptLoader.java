package com.coveo.spillway.storage.utils;

import com.coveo.spillway.exception.SpillwayLuaResourceException;
import com.coveo.spillway.storage.RedisStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;

public class RedisCounterLuaScriptLoader {
  private static final String COUNTER_LUA_SCRIPT_PATH =
      "com/coveo/spillway/storage/redisCounter.lua";

  public static String getCounterLuaScript() throws SpillwayLuaResourceException {
    try {
      return IOUtils.toString(
          RedisStorage.class.getClassLoader().getResourceAsStream(COUNTER_LUA_SCRIPT_PATH),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new SpillwayLuaResourceException(COUNTER_LUA_SCRIPT_PATH, e);
    }
  }
}