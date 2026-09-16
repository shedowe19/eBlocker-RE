package org.restexpress.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enables parsing of the HTTP Forwarded header (RFC7239).
 *  
 * @author tfredrich
 */
public class Forwarded
{
	private static final String BY_TOKEN = "by";
	private static final String FOR_TOKEN = "for";
	private static final String PROTO_TOKEN = "proto";
	private static final String HOST_TOKEN = "host";
	private Map<String, List<ForwardedPair>> parametersByToken;

	private Forwarded(List<ForwardedPair> parameters)
	{
		super();
		this.parametersByToken = new HashMap<>(parameters.size());
		addAll(parameters, parametersByToken);
	}

	private void addAll(List<ForwardedPair> pairs, Map<String, List<ForwardedPair>> parameters)
	{
		if (pairs == null) return;

		pairs.stream().forEach(p -> {
			List<ForwardedPair> l = parameters.computeIfAbsent(p.getToken().toLowerCase(), e -> new ArrayList<>());
			l.add(p);
		});
	}

	public static Forwarded parse(String forwardedHeader)
	throws ForwardedParseError
	{
		return new Forwarded(parseElements(forwardedHeader));
	}

	private static List<ForwardedPair> parseElements(String forwardedHeader)
	throws ForwardedParseError
	{
		if (forwardedHeader == null) return Collections.emptyList();

		String[] elements = forwardedHeader.split("\\s*;\\s*");
		List<ForwardedPair> pairs = new ArrayList<>(elements.length);

		for (String element : elements)
		{
			pairs.addAll(ForwardedElement.parse(element).getPairs());
		}

		return pairs;
	}

	public String getBy()
	{
		return getLastValue(BY_TOKEN);
	}

	public boolean hasBy()
	{
		return hasToken(BY_TOKEN);
	}

	public String getFor()
	{
		return getLastValue(FOR_TOKEN);
	}

	public boolean hasFor()
	{
		return hasToken(FOR_TOKEN);
	}

	public String getHost()
	{
		return getLastValue(HOST_TOKEN);
	}

	public boolean hasHost()
	{
		return hasToken(HOST_TOKEN);
	}

	public String getProto()
	{
		return getLastValue(PROTO_TOKEN);
	}

	public boolean hasProto()
	{
		return hasToken(PROTO_TOKEN);
	}

	public String getLastValue(String token)
	{
		List<ForwardedPair> l = parametersByToken.get(token);
		return (l != null ? l.get(l.size() - 1).getValue() : null);		
	}

	public boolean hasToken(String token)
	{
		return parametersByToken.containsKey(token);
	}
}
