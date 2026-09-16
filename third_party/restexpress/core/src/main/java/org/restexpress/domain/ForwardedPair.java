package org.restexpress.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ForwardedPair
{
	private static final String PARAMETER_REGEX = "(\\w+?)(?:\\s*?=\\s*?\"?(\\S+?)\"?)";
	private static final Pattern PARAMETER_PATTERN = Pattern.compile(PARAMETER_REGEX);

	private String token;
	private String value;

	private ForwardedPair(String token, String value)
	{
		super();
		this.token = token;
		this.value = value;
	}

	public String getToken()
	{
		return token;
	}

	public String getValue()
	{
		return value;
	}

	public static final ForwardedPair parse(String segment)
	throws ForwardedParseError
	{
		Matcher p = PARAMETER_PATTERN.matcher(segment);
		
		if (p.matches())
		{
			String token = p.group(1).toLowerCase();
			String value = p.group(2);
			return new ForwardedPair(token, value);
		}

		throw new ForwardedParseError("Forwarded pair doesn't match pattern: " + segment);
	}
}
