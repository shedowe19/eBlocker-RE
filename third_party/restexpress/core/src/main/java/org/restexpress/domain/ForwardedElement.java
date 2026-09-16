package org.restexpress.domain;

import java.util.ArrayList;
import java.util.List;

public class ForwardedElement
{
	private List<ForwardedPair> pairs;

	public ForwardedElement()
	{
		super();
	}

	private ForwardedElement(List<ForwardedPair> pairs)
	{
		this();
		this.pairs = pairs;
	}

	public List<ForwardedPair> getPairs()
	{
		return pairs;
	}

	public static ForwardedElement parse(String element)
	throws ForwardedParseError
	{
		if (element == null) return new ForwardedElement();

		String[] segments = element.split("\\s*,\\s*");
		List<ForwardedPair> pairs = new ArrayList<>(segments.length);

		for (String segment : segments)
		{
			pairs.add(ForwardedPair.parse(segment));
		}

		return new ForwardedElement(pairs);
	}
}
