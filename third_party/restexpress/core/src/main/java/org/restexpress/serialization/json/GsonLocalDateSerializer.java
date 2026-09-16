/*
 * Copyright 2019, Strategic Gains, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
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

package org.restexpress.serialization.json;

import java.lang.reflect.Type;
import java.text.ParseException;
import java.time.LocalDate;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.strategicgains.util.localdate.LocalDateAdapter;

/**
 * A GSON serializer for Date instances represented (and to be presented) as a date (without time component).
 * 
 * @author Todd Fredrich
 * @since Aug 18, 2019
 */
public class GsonLocalDateSerializer
implements GsonSerializer<LocalDate>
{
	private LocalDateAdapter adapter;
	
	public GsonLocalDateSerializer()
	{
		this(new LocalDateAdapter());
	}
	
	public GsonLocalDateSerializer(LocalDateAdapter adapter)
	{
		super();
		this.adapter = adapter;
	}
	
    @Override
    public LocalDate deserialize(JsonElement json, Type typeOf, JsonDeserializationContext context)
    throws JsonParseException
    {
    	try
        {
	        return adapter.parse(json.getAsJsonPrimitive().getAsString());
        }
        catch (ParseException e)
        {
        	throw new JsonParseException(e);
        }
    }

    @Override
    public JsonElement serialize(LocalDate date, Type typeOf, JsonSerializationContext context)
    {
    	return new JsonPrimitive(adapter.format(date));
    }

    @Override
    public LocalDate createInstance(Type typeOf)
    {
    	return LocalDate.now();
    }
}
