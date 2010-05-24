create table publication
(pub_id integer primary key, title text, abstract text, venue_id integer, url text);

create table author
(author_id integer primary key, author_name text);

create table venue
(venue_id integer primary key, venue_year integer, venue_name text);

create table keyword
(keyword_id integer primary key, word text);

/* either pub_id or missing_ref will be null */
create table reference
(ref_id integer primary key, pub_id integer, missing_ref text);

/* relational entities */

create table publication_author
(pub_id integer, author_id integer, primary key (pub_id, author_id) );

create table publication_keyword
(pub_id integer, keyword_id integer, primary key (pub_id, keyword_id) );

create table publication_reference
(pub_id integer, ref_id integer, primary key (pub_id, ref_id) );

